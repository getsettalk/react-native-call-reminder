package com.callreminder

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.annotation.SuppressLint
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityManager
import android.animation.ValueAnimator
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * "Swipe up to answer / decline" for one ringing button: the circle follows
 * the finger 1:1 up to the commit point, then with increasing resistance, and
 * commits when released past [COMMIT_FRACTION] of the drag distance (or on a
 * fast upward fling); otherwise it springs back. Past the commit point it grows
 * slightly (with a haptic tick) so the user knows letting go will act. While
 * idle, the chevron above it floats upwards to invite the gesture. A plain tap never commits (no pocket answers) — it bounces
 * the circle and emphasises the hint instead.
 *
 * Screen-reader, Switch Access and keyboard users cannot drag: they activate
 * the button through its click / custom accessibility actions, which the call
 * screen wires to [commit] directly. A tap that reaches us while touch
 * exploration is on (a screen reader simulating a tap) commits too.
 *
 * Buttons of one [Group] are exclusive: while one is touched the others
 * ignore new touches, and only the first pointer of a gesture is tracked.
 */
internal class SwipeUpButton(
    private val circle: View,
    private val chevron: View,
    private val hint: View,
    private val group: Group,
    private val hintAlpha: Float,
    private val onCommit: () -> Unit,
) : View.OnTouchListener {

  /** Shared by the buttons of one screen: at most one is dragged at a time. */
  class Group(
      /** True while any member is touched or settling (e.g. to pause other animations). */
      val onBusyChanged: (Boolean) -> Unit,
  ) {
    internal val members = mutableListOf<SwipeUpButton>()
    internal var owner: SwipeUpButton? = null
    private var busy = false

    internal fun updateBusy() {
      val now = owner != null || members.any { it.settling }
      if (now != busy) {
        busy = now
        onBusyChanged(now)
      }
    }

    /** Puts every member back at rest, abandoning any drag (e.g. the call stopped ringing). */
    fun reset() {
      owner = null
      members.forEach { it.resetInternal() }
      updateBusy()
    }
  }

  private val density = circle.resources.displayMetrics.density
  private val touchSlop = ViewConfiguration.get(circle.context).scaledTouchSlop
  private val accessibility = circle.context.getSystemService(AccessibilityManager::class.java)

  private var pointerId = INVALID_POINTER
  private var startX = 0f
  private var startY = 0f
  private var downTime = 0L
  private var dragging = false
  /** Moved past the touch slop in any direction: no longer a tap. */
  private var moved = false
  private var armed = false
  private var distance = 0f
  private var velocity: VelocityTracker? = null
  private var settle: Animator? = null
  private var emphasis: Animator? = null
  private var settling = false
  /** The chevron's idle "float up" loop; stopped while touched and when the screen goes away. */
  private var invite: Animator? = null

  init {
    group.members += this
  }

  /** Starts listening for the gesture on the circle. */
  @SuppressLint("ClickableViewAccessibility") // clicks stay for accessibility services, see the class doc
  fun attach() {
    circle.setOnTouchListener(this)
    // An infinite animator keeps its view (and so the activity) alive: tie it to the window.
    chevron.addOnAttachStateChangeListener(
        object : View.OnAttachStateChangeListener {
          override fun onViewAttachedToWindow(v: View) = startInvite()

          override fun onViewDetachedFromWindow(v: View) = stopInvite()
        })
    if (chevron.isAttachedToWindow) startInvite()
  }

  /** Commits as if dragged: haptic confirmation, then [onCommit]; the circle settles back. */
  fun commit() {
    circle.performHapticFeedback(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM
        else HapticFeedbackConstants.VIRTUAL_KEY)
    onCommit()
    // The screen normally switches state right away; if the call keeps ringing
    // (e.g. the user cancelled the unlock of a private call) it is back at rest.
    springBack()
  }

  /**
   * Always consumes: letting a touch through to the button's own onTouchEvent
   * would turn a plain tap into performClick(), which commits.
   */
  @SuppressLint("ClickableViewAccessibility")
  override fun onTouch(view: View, event: MotionEvent): Boolean {
    when (event.actionMasked) {
      MotionEvent.ACTION_DOWN -> {
        val current = group.owner
        if (current != null && current !== this) return true // another button is being dragged: ignore
        group.owner = this
        pointerId = event.getPointerId(0)
        startX = event.getX(0) + view.translationX
        startY = event.getY(0) + view.translationY
        downTime = event.eventTime
        dragging = false
        moved = false
        armed = false
        distance = dragDistance()
        settle?.cancel()
        stopInvite()
        calmHint()
        velocity?.recycle()
        velocity = VelocityTracker.obtain().also { track(it, event) }
        view.drawableHotspotChanged(event.getX(0), event.getY(0))
        view.isPressed = true
        group.updateBusy()
        return true
      }
      MotionEvent.ACTION_MOVE -> {
        if (group.owner !== this || pointerId == INVALID_POINTER) return true
        val index = event.findPointerIndex(pointerId)
        if (index < 0) return true
        velocity?.let { track(it, event) }
        val travel = startY - (event.getY(index) + view.translationY)
        if (!dragging) {
          val sideways = abs(event.getX(index) + view.translationX - startX)
          if (abs(travel) > touchSlop || sideways > touchSlop) moved = true
          if (travel <= touchSlop || sideways > travel) return true
          dragging = true
          view.isPressed = false
          view.parent?.requestDisallowInterceptTouchEvent(true)
        }
        follow(travel)
        return true
      }
      // A second finger is ignored; lifting the tracked one ends the gesture.
      MotionEvent.ACTION_POINTER_UP -> {
        if (group.owner === this && event.getPointerId(event.actionIndex) == pointerId) {
          finish(view, event, cancelled = false)
        }
        return true
      }
      MotionEvent.ACTION_UP -> {
        if (group.owner === this) finish(view, event, cancelled = false)
        return true
      }
      MotionEvent.ACTION_CANCEL -> {
        if (group.owner === this) finish(view, event, cancelled = true)
        return true
      }
    }
    return true
  }

  // ---------------------------------------------------------------------------

  /** Feeds the tracker stable coordinates (the view moves under the finger). */
  private fun track(tracker: VelocityTracker, event: MotionEvent) {
    val stable = MotionEvent.obtain(event)
    stable.offsetLocation(circle.translationX, circle.translationY)
    tracker.addMovement(stable)
    stable.recycle()
  }

  /** 1:1 up to the commit point, then a rubber band approaching [distance] asymptotically. */
  private fun follow(travel: Float) {
    val pulled = travel.coerceAtLeast(0f)
    val commitAt = distance * COMMIT_FRACTION
    val slack = distance - commitAt
    val shown = if (pulled <= commitAt) pulled else commitAt + slack * (1f - exp(-(pulled - commitAt) / slack))
    circle.translationY = -shown
    val progress = (pulled / (distance * COMMIT_FRACTION)).coerceIn(0f, 1f)
    chevron.alpha = 1f - progress
    hint.alpha = hintAlpha * (1f - 0.5f * progress)
    group.members.forEach { if (it !== this) it.circle.alpha = 1f - 0.5f * progress }
    val nowArmed = pulled >= distance * COMMIT_FRACTION
    if (nowArmed != armed) {
      armed = nowArmed
      if (nowArmed) circle.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
      circle
          .animate()
          .scaleX(if (nowArmed) ARMED_SCALE else 1f)
          .scaleY(if (nowArmed) ARMED_SCALE else 1f)
          .setDuration(140)
          .setInterpolator(DecelerateInterpolator())
          .start()
    }
  }

  private fun finish(view: View, event: MotionEvent, cancelled: Boolean) {
    view.isPressed = false
    var flingVelocity = 0f
    velocity?.let {
      track(it, event)
      it.computeCurrentVelocity(1000)
      flingVelocity = it.getYVelocity(pointerId)
      it.recycle()
    }
    velocity = null
    val index = event.findPointerIndex(pointerId)
    val travel = if (index >= 0) startY - (event.getY(index) + view.translationY) else 0f
    val wasDragging = dragging
    val tapped =
        !wasDragging &&
            !moved &&
            !cancelled &&
            event.actionMasked == MotionEvent.ACTION_UP &&
            event.eventTime - downTime < ViewConfiguration.getLongPressTimeout()
    dragging = false
    moved = false
    armed = false
    pointerId = INVALID_POINTER
    group.owner = null
    when {
      cancelled -> springBack()
      wasDragging &&
          (travel >= distance * COMMIT_FRACTION ||
              (flingVelocity <= -FLING_DP_PER_S * density && travel >= FLING_MIN_TRAVEL_DP * density)) -> commit()
      tapped && accessibility?.isTouchExplorationEnabled == true -> commit()
      tapped -> bounce()
      else -> springBack()
    }
    group.updateBusy()
  }

  /** A tap: hop up and back, and briefly emphasise the hint. */
  private fun bounce() {
    settle?.cancel()
    val hop = HOP_DP * density
    animate(
        ObjectAnimator.ofFloat(circle, View.TRANSLATION_Y, circle.translationY, -hop, 0f, -hop * 0.35f, 0f).apply {
          duration = 700
          interpolator = DecelerateInterpolator()
        })
    emphasizeHint()
  }

  private fun springBack() {
    settle?.cancel()
    animate(
        AnimatorSet().apply {
          playTogether(
              buildList {
                add(ObjectAnimator.ofFloat(circle, View.TRANSLATION_Y, 0f))
                add(ObjectAnimator.ofFloat(circle, View.SCALE_X, 1f))
                add(ObjectAnimator.ofFloat(circle, View.SCALE_Y, 1f))
                add(ObjectAnimator.ofFloat(chevron, View.ALPHA, 1f))
                add(ObjectAnimator.ofFloat(hint, View.ALPHA, hintAlpha))
                group.members.forEach { if (it !== this@SwipeUpButton) add(ObjectAnimator.ofFloat(it.circle, View.ALPHA, 1f)) }
              })
          duration = 320
          interpolator = OvershootInterpolator(1.4f)
        })
  }

  private fun emphasizeHint() {
    emphasis?.cancel()
    emphasis =
        AnimatorSet().apply {
          val strong =
              ObjectAnimator.ofPropertyValuesHolder(
                      hint,
                      PropertyValuesHolder.ofFloat(View.ALPHA, 1f),
                      PropertyValuesHolder.ofFloat(View.SCALE_X, 1.08f),
                      PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.08f))
                  .apply { duration = 180 }
          val calm =
              ObjectAnimator.ofPropertyValuesHolder(
                      hint,
                      PropertyValuesHolder.ofFloat(View.ALPHA, hintAlpha),
                      PropertyValuesHolder.ofFloat(View.SCALE_X, 1f),
                      PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f))
                  .apply {
                    duration = 320
                    startDelay = 1_400
                  }
          // The chevron's own idle float (restarted once the bounce settles) carries the motion cue.
          playSequentially(strong, calm)
          start()
        }
  }

  private fun calmHint() {
    if (emphasis == null) return
    emphasis?.cancel()
    emphasis = null
    hint.alpha = hintAlpha
    hint.scaleX = 1f
    hint.scaleY = 1f
  }

  /** Runs [animator] as the settle animation, keeping the group's busy state in sync. */
  private fun animate(animator: Animator) {
    // Assigned before start(): with animations disabled it may end synchronously.
    settle = animator
    settling = true
    animator.addListener(
        object : AnimatorListenerAdapter() {
          private var cancelled = false

          override fun onAnimationCancel(animation: Animator) {
            cancelled = true
          }

          override fun onAnimationEnd(animation: Animator) {
            // A cancelled settle was replaced by a new touch or animation.
            if (!cancelled && settle === animation) {
              settling = false
              settle = null
              group.updateBusy()
              if (group.owner == null && chevron.isAttachedToWindow) startInvite()
            }
          }
        })
    animator.start()
  }

  private fun resetInternal() {
    settle?.cancel()
    settle = null
    settling = false
    emphasis?.cancel()
    emphasis = null
    velocity?.recycle()
    velocity = null
    pointerId = INVALID_POINTER
    dragging = false
    moved = false
    armed = false
    circle.isPressed = false
    circle.animate().cancel()
    circle.translationY = 0f
    circle.scaleX = 1f
    circle.scaleY = 1f
    circle.alpha = 1f
    stopInvite()
    chevron.alpha = 1f
    chevron.translationY = 0f
    if (chevron.isAttachedToWindow) startInvite()
    hint.alpha = hintAlpha
    hint.scaleX = 1f
    hint.scaleY = 1f
  }

  /** Floats the chevron up while fading it, then rests — repeated until touched or detached. */
  private fun startInvite() {
    if (invite != null || !ValueAnimator.areAnimatorsEnabled()) return
    val rise = -INVITE_RISE_DP * density
    invite =
        ObjectAnimator.ofPropertyValuesHolder(
                chevron,
                PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, 0f, rise * 0.6f, rise),
                PropertyValuesHolder.ofFloat(View.ALPHA, 1f, 1f, 0f))
            .apply {
              duration = 1_100
              startDelay = 300
              repeatCount = ValueAnimator.INFINITE
              repeatMode = ValueAnimator.RESTART
              interpolator = AccelerateDecelerateInterpolator()
              start()
            }
  }

  private fun stopInvite() {
    val running = invite ?: return
    invite = null
    running.cancel()
    chevron.translationY = 0f
    chevron.alpha = 1f
  }

  /** [MAX_DISTANCE_DP], shortened on small/landscape screens. */
  private fun dragDistance(): Float {
    val max = MAX_DISTANCE_DP * density
    val screen = circle.rootView?.height?.takeIf { it > 0 }?.toFloat() ?: return max
    return min(max, max(MIN_DISTANCE_DP * density, screen / 4f))
  }

  private companion object {
    const val INVALID_POINTER = -1
    const val MAX_DISTANCE_DP = 140f
    const val MIN_DISTANCE_DP = 90f
    /** Fraction of the drag distance the finger must travel to commit. */
    const val COMMIT_FRACTION = 0.4f
    const val FLING_DP_PER_S = 1_000f
    const val FLING_MIN_TRAVEL_DP = 24f
    const val HOP_DP = 26f
    const val INVITE_RISE_DP = 8f
    const val ARMED_SCALE = 1.12f
  }
}
