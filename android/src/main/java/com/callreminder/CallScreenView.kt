package com.callreminder

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.BackgroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.Chronometer
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * The call screen, built in code so the library imposes no theme or layout
 * resources on the host app. It is a pure view: [IncomingCallActivity] feeds
 * it state and receives the user's choices through [Listener].
 */
internal class CallScreenView(
    private val context: Context,
    private val config: CallReminderConfig,
    private val listener: Listener,
) {
  interface Listener {
    fun onAnswer()

    fun onDecline()

    fun onEndCall()

    fun onReplay()

    fun onAction(actionId: String)
  }

  private val screenColor = config.backgroundColor(context)
  private val accent = config.accentColor(context)
  private val textColor = config.textColor(context)
  private val answerColor = ContextCompat.getColor(context, R.color.callreminder_answer)
  private val declineColor = ContextCompat.getColor(context, R.color.callreminder_decline)
  private val landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

  val root = FrameLayout(context).apply { setBackgroundColor(screenColor) }
  private val content = column(Gravity.CENTER_HORIZONTAL)
  /**
   * Everything above the buttons scrolls, so Answer/Decline (and the actions
   * once answered) stay on screen at any font scale, display size or
   * orientation.
   */
  private val scroll =
      ScrollView(context).apply {
        isFillViewport = true
        isVerticalScrollBarEnabled = false
      }
  private val details = column(Gravity.CENTER_HORIZONTAL)

  private val header = text(14f, alpha = 0.75f).apply { letterSpacing = 0.06f }
  private val brand = text(15f, alpha = 0.9f, bold = true)
  private val pulseRings = List(2) { View(context).apply { background = oval(accent); alpha = 0f } }
  private val avatar = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
  private val avatarFrame = FrameLayout(context)
  private val callerName = text(30f, bold = true).apply { maxLines = 2 }
  private val title = text(18f).apply { maxLines = 2 }
  private val body = text(15f, alpha = 0.8f).apply { maxLines = 4 }

  private val status =
      text(15f, alpha = 0.85f).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
  private val chronometer =
      Chronometer(context).apply {
        setTextColor(textColor)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        alpha = 0.85f
        setOnChronometerTickListener { meter ->
          meter.contentDescription = context.getString(R.string.callreminder_a11y_call_duration, meter.text)
        }
      }
  private val statusRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
  private val transcript =
      text(20f, gravity = Gravity.START).apply {
        setLineSpacing(0f, 1.25f)
        setTextIsSelectable(false)
      }
  /** Side by side in landscape, where height is scarce. */
  private val actions =
      LinearLayout(context).apply {
        orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
      }

  /** Lets the Answer button's breathing circle draw past the row's edges instead of being clipped. */
  private val ringingControls =
      LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        clipChildren = false
      }
  private val activeControls = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
  private val answerButton: View
  private val declineButton: View
  /** The Answer button's circle (without its label): what the ringing nudge animates. */
  private val answerCircle: View

  private var pulse: Animator? = null
  private var nudge: Animator? = null
  private var boundCallId: String? = null
  private var boundActions: List<CallAction>? = null
  /** Call details are hidden (private call on a secure lock screen). */
  private var concealed = false
  private var transcriptText: String = ""

  init {
    root.addView(content, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    content.setPadding(dp(24), dp(16), dp(24), dp(24))
    ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
      val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
      view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
      WindowInsetsCompat.CONSUMED
    }

    content.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
    scroll.addView(details, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

    details.addView(header, wrap(top = 8))
    details.addView(brand, wrap(top = 4))

    val ringSize = dp(176)
    pulseRings.forEach { ring -> avatarFrame.addView(ring, FrameLayout.LayoutParams(ringSize, ringSize, Gravity.CENTER)) }
    avatarFrame.addView(avatar, FrameLayout.LayoutParams(dp(112), dp(112), Gravity.CENTER))
    details.addView(avatarFrame, LinearLayout.LayoutParams(ringSize, ringSize).apply { topMargin = dp(16) })
    if (landscape) avatarFrame.visibility = View.GONE

    details.addView(callerName, wrap(top = 16))
    details.addView(title, wrap(top = 6))
    details.addView(body, wrap(top = 6))

    statusRow.addView(status, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
    statusRow.addView(text(15f, alpha = 0.6f).apply { text = "  ·  " })
    statusRow.addView(chronometer, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
    details.addView(statusRow, wrap(top = 12))
    details.addView(transcript, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(16) })

    content.addView(actions, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) })

    declineButton =
        roundButton(R.drawable.callreminder_ic_call_end, declineColor, config.label(context, Label.DECLINE), 72) {
          listener.onDecline()
        }
    answerButton =
        roundButton(R.drawable.callreminder_ic_call, answerColor, config.label(context, Label.ANSWER), 72) {
          listener.onAnswer()
        }
    answerCircle = (answerButton as ViewGroup).getChildAt(0)
    ringingControls.addView(declineButton, weighted())
    ringingControls.addView(answerButton, weighted())
    content.addView(ringingControls, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(16) })

    activeControls.addView(
        roundButton(R.drawable.callreminder_ic_replay, ColorUtils.setAlphaComponent(textColor, 0x29), config.label(context, Label.REPLAY), 60) {
          listener.onReplay()
        },
        weighted())
    activeControls.addView(
        roundButton(R.drawable.callreminder_ic_call_end, declineColor, config.label(context, Label.END_CALL), 60) {
          listener.onEndCall()
        },
        weighted())
    content.addView(activeControls, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(16) })

    header.text = config.label(context, Label.INCOMING_TITLE)
    brand.text = config.brandName(context)
  }

  /**
   * Call details; rebinding the same call in the same privacy state only
   * refreshes the transcript. [concealed] shows nothing but the app's brand
   * (a `private` call over a secure lock screen).
   */
  fun bind(call: IncomingCall, spokenText: String, concealed: Boolean) {
    if (boundCallId != call.callId || this.concealed != concealed) {
      boundCallId = call.callId
      boundActions = null
      this.concealed = concealed
      if (concealed) {
        callerName.text = config.brandName(context)
        title.text = ""
        body.text = ""
      } else {
        callerName.text = call.callerName
        title.text = call.title
        body.text = call.body
      }
      // While concealed the brand is the caller line (see showRinging/showActive).
      title.visibility = if (concealed) View.GONE else View.VISIBLE
      val bitmap =
          (if (concealed) CallResources.brandAvatar(context, config) else CallResources.avatar(context, config, call))
              ?: CallResources.appIcon(context)
      if (bitmap != null) {
        avatar.setImageDrawable(RoundedBitmapDrawableFactory.create(context.resources, bitmap).apply { isCircular = true })
        avatar.scaleType = ImageView.ScaleType.CENTER_CROP
        avatar.background = null
      } else {
        avatar.setImageResource(R.drawable.callreminder_ic_notification)
        avatar.scaleType = ImageView.ScaleType.CENTER
        avatar.background = oval(accent)
      }
      transcriptText = ""
    }
    showTranscript(spokenText)
  }

  /** The text being spoken (the call's speakText, or its fallback wording). */
  fun showTranscript(text: String) {
    if (text == transcriptText) return
    transcriptText = text
    transcript.text = if (concealed) "" else text
  }

  fun showRinging() {
    statusRow.visibility = View.GONE
    transcript.visibility = View.GONE
    actions.visibility = View.GONE
    activeControls.visibility = View.GONE
    ringingControls.visibility = View.VISIBLE
    body.visibility = if (concealed) View.GONE else View.VISIBLE
    brand.visibility = if (concealed) View.GONE else View.VISIBLE
    setControlsEnabled(true)
    chronometer.stop()
    header.text = config.label(context, Label.INCOMING_TITLE)
    startRingingAnimations()
  }

  fun showActive(record: CallRecord, callActions: List<CallAction>, speech: CallController.SpeechStatus) {
    stopRingingAnimations()
    ringingControls.visibility = View.GONE
    body.visibility = View.GONE
    statusRow.visibility = View.VISIBLE
    // Spoken aloud regardless; only the screen keeps it private until unlock.
    transcript.visibility = if (concealed) View.GONE else View.VISIBLE
    actions.visibility = View.VISIBLE
    activeControls.visibility = View.VISIBLE
    setControlsEnabled(true)
    header.text = config.brandName(context)
    brand.visibility = View.GONE
    chronometer.base = SystemClock.elapsedRealtime() - (System.currentTimeMillis() - record.answeredAt).coerceAtLeast(0)
    chronometer.start()
    bindActions(callActions)
    showSpeech(speech)
  }

  fun showSpeech(speech: CallController.SpeechStatus) {
    status.text =
        config.label(
            context, if (speech == CallController.SpeechStatus.SPEAKING) Label.SPEAKING else Label.LISTENING)
    if (speech != CallController.SpeechStatus.SPEAKING && !concealed) {
      transcript.text = transcriptText
    }
  }

  /** Highlights the words being spoken and keeps them in view. */
  fun highlight(start: Int, end: Int) {
    if (concealed || start < 0 || end > transcriptText.length || start >= end) return
    val spannable = SpannableString(transcriptText)
    spannable.setSpan(
        BackgroundColorSpan(ColorUtils.setAlphaComponent(accent, 0x66)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    transcript.text = spannable
    transcript.post {
      val layout = transcript.layout ?: return@post
      val lineTop = transcript.top + transcript.paddingTop + layout.getLineTop(layout.getLineForOffset(start))
      scroll.smoothScrollTo(0, (lineTop - scroll.height / 3).coerceAtLeast(0))
    }
  }

  fun showEnded() {
    stopRingingAnimations()
    chronometer.stop()
    setControlsEnabled(false)
    status.text = config.label(context, Label.ENDED)
    header.text = config.label(context, Label.ENDED)
    actions.removeAllViews()
    boundActions = null
  }

  fun release() {
    stopRingingAnimations()
    chronometer.stop()
  }

  // ---------------------------------------------------------------------------

  private fun bindActions(callActions: List<CallAction>) {
    if (boundActions == callActions) return
    boundActions = callActions
    actions.removeAllViews()
    callActions.forEachIndexed { index, action ->
      val params =
          if (landscape) {
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
              topMargin = dp(10)
              if (index > 0) marginStart = dp(10)
            }
          } else {
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) }
          }
      actions.addView(actionButton(action), params)
    }
  }

  private fun setControlsEnabled(enabled: Boolean) {
    listOf(ringingControls, activeControls, actions).forEach { group ->
      for (index in 0 until group.childCount) setEnabledDeep(group.getChildAt(index), enabled)
    }
  }

  private fun setEnabledDeep(view: View, enabled: Boolean) {
    view.isEnabled = enabled
    if (view is LinearLayout) for (index in 0 until view.childCount) setEnabledDeep(view.getChildAt(index), enabled)
  }

  private fun startRingingAnimations() {
    if (pulse == null && avatarFrame.visibility == View.VISIBLE) {
      pulse =
          AnimatorSet().apply {
            playTogether(
                pulseRings.mapIndexed { index, ring ->
                  AnimatorSet().apply {
                    playTogether(
                        repeating(ObjectAnimator.ofFloat(ring, View.SCALE_X, 0.64f, 1f)),
                        repeating(ObjectAnimator.ofFloat(ring, View.SCALE_Y, 0.64f, 1f)),
                        repeating(ObjectAnimator.ofFloat(ring, View.ALPHA, 0.45f, 0f)))
                    startDelay = index * PULSE_MS / 2
                  }
                })
            start()
          }
    }
    if (nudge == null) {
      // The Answer circle "breathes" in place (grow, settle, rest) — scaled
      // about its own centre so it stays level with Decline, never moved up.
      nudge =
          ObjectAnimator.ofPropertyValuesHolder(
                  answerCircle,
                  PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, NUDGE_SCALE, 1f, 1f),
                  PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, NUDGE_SCALE, 1f, 1f))
              .apply {
                duration = 1_200
                repeatCount = ValueAnimator.INFINITE
                startDelay = 400
                start()
              }
    }
  }

  private fun stopRingingAnimations() {
    pulse?.cancel()
    pulse = null
    nudge?.cancel()
    nudge = null
    pulseRings.forEach { it.alpha = 0f }
    answerCircle.scaleX = 1f
    answerCircle.scaleY = 1f
  }

  private fun repeating(animator: ObjectAnimator): ObjectAnimator =
      animator.apply {
        duration = PULSE_MS
        repeatCount = ValueAnimator.INFINITE
      }

  private fun roundButton(icon: Int, color: Int, label: String, sizeDp: Int, onClick: () -> Unit): View {
    val button =
        ImageButton(context).apply {
          setImageResource(icon)
          imageTintList = ColorStateList.valueOf(contrastOn(color))
          scaleType = ImageView.ScaleType.FIT_CENTER
          val inset = dp(sizeDp) * 3 / 10
          setPadding(inset, inset, inset, inset)
          background = ripple(oval(color))
          contentDescription = label
          setOnClickListener { onClick() }
        }
    return column(Gravity.CENTER_HORIZONTAL).apply {
      clipChildren = false // a scaled (animated) circle may draw past the column
      addView(button, LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)))
      addView(text(14f, alpha = 0.9f).apply {
        text = label
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
      }, wrap(top = 8))
    }
  }

  private fun actionButton(action: CallAction): View {
    val fill =
        when (action.style) {
          CallAction.STYLE_PRIMARY -> accent
          CallAction.STYLE_DESTRUCTIVE -> declineColor
          else -> ColorUtils.setAlphaComponent(textColor, 0x29)
        }
    val label = if (action.style == CallAction.STYLE_SECONDARY) textColor else contrastOn(fill)
    return Button(context).apply {
      text = action.label
      isAllCaps = false
      stateListAnimator = null
      gravity = Gravity.CENTER
      minHeight = dp(52)
      setPadding(dp(20), dp(12), dp(20), dp(12))
      setTextColor(label)
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
      typeface = Typeface.DEFAULT_BOLD
      background = ripple(GradientDrawable().apply { cornerRadius = dp(26).toFloat(); setColor(fill) })
      setOnClickListener { listener.onAction(action.id) }
    }
  }

  private fun ripple(content: Drawable): Drawable =
      RippleDrawable(ColorStateList.valueOf(ColorUtils.setAlphaComponent(textColor, 0x40)), content, null)

  private fun oval(color: Int) = GradientDrawable().apply {
    shape = GradientDrawable.OVAL
    setColor(color)
  }

  private fun contrastOn(color: Int): Int = if (ColorUtils.calculateLuminance(color) > 0.55) Color.BLACK else Color.WHITE

  private fun column(gravity: Int) = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    this.gravity = gravity
  }

  private fun text(sizeSp: Float, alpha: Float = 1f, bold: Boolean = false, gravity: Int = Gravity.CENTER) =
      TextView(context).apply {
        setTextColor(textColor)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        this.alpha = alpha
        this.gravity = gravity
        ellipsize = TextUtils.TruncateAt.END
        if (bold) typeface = Typeface.DEFAULT_BOLD
      }

  private fun wrap(top: Int = 0) = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(top) }

  private fun weighted() = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)

  private fun dp(value: Int): Int =
      TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics).toInt()

  private companion object {
    const val PULSE_MS = 1_600L
    /** Peak size of the Answer circle's breathing nudge (×). */
    const val NUDGE_SCALE = 1.1f
  }
}
