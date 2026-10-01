import UIKit

/**
 * In-app call screen shown after the user answers (or while a call rings with
 * the app open). Built in code so the library needs no storyboard or assets.
 * It holds no call state: [CallReminderCore] binds a record and pushes
 * changes; the user's choices go straight back to the core.
 */
final class CallScreenViewController: UIViewController {
  private(set) var callId: String?

  private unowned let core: CallReminderCore
  private let config: CallReminderConfig
  private var answeredAt: Date?
  private var transcriptText = ""
  private var boundActions: [CallAction]?
  private var timer: Timer?
  private var dismissWork: DispatchWorkItem?

  private let content = UIStackView()
  private let headerLabel = UILabel()
  private let brandLabel = UILabel()
  private let avatarRow = UIStackView()
  private let avatarContainer = UIView()
  private let avatarView = UIImageView()
  private let pulseLayers = [CAShapeLayer(), CAShapeLayer()]
  private let callerLabel = UILabel()
  private let titleLabel = UILabel()
  private let bodyLabel = UILabel()
  private let statusLabel = UILabel()
  private let timerLabel = UILabel()
  private let transcriptView = UITextView()
  private let actionsStack = UIStackView()
  private let activePanel = UIStackView()
  private let spacer = UIView()
  private let ringingControls = UIStackView()
  private let activeControls = UIStackView()
  private var answerButton: UIButton?
  /// `answerGesture: 'swipe'`: the ringing buttons are dragged upwards.
  private let swipeGroup = SwipeUpControl.Group()
  private var swipeControls: [SwipeUpControl] = []
  private var ringing = false

  private static let avatarSize: CGFloat = 104
  private static let pulseSize: CGFloat = 168
  private static let endedLinger: TimeInterval = 1.2
  private static let minHeightForAvatar: CGFloat = 560
  /// Peak size of the Answer circle's breathing nudge (×).
  private static let nudgeScale: CGFloat = 1.1
  /// Resting opacity of the "Swipe up to …" hints.
  private static let swipeHintAlpha: CGFloat = 0.6

  init(core: CallReminderCore, config: CallReminderConfig) {
    self.core = core
    self.config = config
    super.init(nibName: nil, bundle: nil)
    modalPresentationStyle = .fullScreen
    modalTransitionStyle = .crossDissolve
    // A call is dismissed through its buttons, never by a swipe.
    isModalInPresentation = true
  }

  @available(*, unavailable)
  required init?(coder: NSCoder) {
    fatalError("init(coder:) is not supported")
  }

  private var isDarkBackground: Bool {
    var white: CGFloat = 0
    config.backgroundColor.getWhite(&white, alpha: nil)
    return white < 0.5
  }

  override var preferredStatusBarStyle: UIStatusBarStyle {
    isDarkBackground ? .lightContent : .darkContent
  }

  override func viewDidLoad() {
    super.viewDidLoad()
    overrideUserInterfaceStyle = isDarkBackground ? .dark : .light
    view.backgroundColor = config.backgroundColor
    buildLayout()
    if let callId, let record = core.store.get(callId) {
      render(record)
      showSpeech(core.speechStatus(callId))
    }
  }

  override func viewDidLayoutSubviews() {
    super.viewDidLayoutSubviews()
    // Short (landscape) screens lack the height for the avatar.
    let compact = view.bounds.height < Self.minHeightForAvatar
    if avatarRow.isHidden != compact {
      avatarRow.isHidden = compact
    }
    let bounds = avatarContainer.bounds
    for layer in pulseLayers {
      layer.frame = bounds
      layer.path = UIBezierPath(ovalIn: bounds).cgPath
    }
  }

  override func viewDidDisappear(_ animated: Bool) {
    super.viewDidDisappear(animated)
    if isBeingDismissed || presentingViewController == nil {
      stopTimer()
      stopRingingAnimations()
    }
  }

  // MARK: - State (driven by CallReminderCore)

  func bind(_ record: CallRecord, speech: CallReminderCore.SpeechStatus) {
    if callId != record.callId {
      callId = record.callId
      answeredAt = nil
      boundActions = nil
      dismissWork?.cancel()
      dismissWork = nil
      let call = record.call
      callerLabel.text = call.callerName
      titleLabel.text = call.title
      bodyLabel.text = call.body
      transcriptText = core.transcript(for: record)
      setTranscript(highlight: nil)
      avatarView.image = avatarImage(for: call)
    }
    render(record)
    showSpeech(speech)
  }

  /// Renders the call, or the ended state when `record` is nil.
  func render(_ record: CallRecord?) {
    guard isViewLoaded else { return }
    guard let record else {
      showEnded()
      return
    }
    dismissWork?.cancel()
    dismissWork = nil
    switch record.state {
    case .ringing:
      showRinging()
    case .active:
      showActive(record)
    }
  }

  func showSpeech(_ status: CallReminderCore.SpeechStatus) {
    guard isViewLoaded else { return }
    statusLabel.text = config.label(status == .speaking ? .speaking : .listening)
    if status != .speaking {
      setTranscript(highlight: nil)
    }
  }

  /// The text being spoken (the call's speakText, or its fallback wording).
  func showTranscript(_ text: String) {
    guard text != transcriptText else { return }
    transcriptText = text
    setTranscript(highlight: nil)
  }

  /// Highlights the words being spoken and keeps them in view.
  func highlight(_ range: NSRange) {
    guard isViewLoaded, range.location != NSNotFound, range.length > 0,
          NSMaxRange(range) <= (transcriptText as NSString).length
    else { return }
    setTranscript(highlight: range)
    transcriptView.scrollRangeToVisible(range)
  }

  private func showRinging() {
    activePanel.isHidden = true
    activeControls.isHidden = true
    spacer.isHidden = false
    ringingControls.isHidden = false
    bodyLabel.isHidden = false
    brandLabel.isHidden = false
    headerLabel.text = config.label(.incomingTitle)
    setControlsEnabled(true)
    stopTimer()
    if !ringing { swipeGroup.reset() }
    ringing = true
    startRingingAnimations()
  }

  private func showActive(_ record: CallRecord) {
    stopRingingAnimations()
    spacer.isHidden = true
    ringingControls.isHidden = true
    bodyLabel.isHidden = true
    brandLabel.isHidden = true
    activePanel.isHidden = false
    activeControls.isHidden = false
    headerLabel.text = config.brandName
    setControlsEnabled(true)
    bindActions(record.call.resolvedActions(config))
    if answeredAt != record.answeredAt {
      answeredAt = record.answeredAt
      startTimer()
      UIAccessibility.post(notification: .screenChanged, argument: statusLabel)
    }
  }

  private func showEnded() {
    stopRingingAnimations()
    stopTimer()
    setControlsEnabled(false)
    headerLabel.text = config.label(.ended)
    statusLabel.text = config.label(.ended)
    UIAccessibility.post(notification: .announcement, argument: config.label(.ended))
    guard dismissWork == nil else { return }
    let work = DispatchWorkItem { [weak self] in
      guard let self else { return }
      self.dismissWork = nil
      self.presentingViewController?.dismiss(animated: true)
    }
    dismissWork = work
    DispatchQueue.main.asyncAfter(deadline: .now() + Self.endedLinger, execute: work)
  }

  // MARK: - User actions

  @objc private func answerTapped() {
    if let callId { core.answer(callId) }
  }

  @objc private func declineTapped() {
    if let callId { core.decline(callId, reason: nil) }
  }

  @objc private func endTapped() {
    if let callId { core.end(callId, reason: Reason.user) }
  }

  @objc private func replayTapped() {
    if let callId { core.replay(callId) }
  }

  // MARK: - Layout

  private func buildLayout() {
    content.axis = .vertical
    content.alignment = .fill
    content.spacing = 8
    content.translatesAutoresizingMaskIntoConstraints = false
    view.addSubview(content)
    let guide = view.safeAreaLayoutGuide
    NSLayoutConstraint.activate([
      content.topAnchor.constraint(equalTo: guide.topAnchor, constant: 24),
      content.bottomAnchor.constraint(equalTo: guide.bottomAnchor, constant: -24),
      content.leadingAnchor.constraint(equalTo: guide.leadingAnchor, constant: 24),
      content.trailingAnchor.constraint(equalTo: guide.trailingAnchor, constant: -24),
    ])

    style(headerLabel, .subheadline, alpha: 0.75)
    style(brandLabel, .subheadline, alpha: 0.9, weight: .semibold)
    brandLabel.text = config.brandName
    style(callerLabel, .largeTitle, weight: .bold, lines: 2)
    style(titleLabel, .title3, lines: 2)
    style(bodyLabel, .body, alpha: 0.8, lines: 4)
    style(statusLabel, .subheadline, alpha: 0.85)
    statusLabel.accessibilityTraits.insert(.updatesFrequently)
    style(timerLabel, .subheadline, alpha: 0.85)
    timerLabel.font = UIFontMetrics(forTextStyle: .subheadline)
      .scaledFont(for: .monospacedDigitSystemFont(ofSize: Self.baseSize(.subheadline), weight: .regular))

    // Avatar with the ringing pulse behind it, centred in a full-width row.
    for layer in pulseLayers {
      layer.fillColor = config.accentColor.cgColor
      layer.opacity = 0
      avatarContainer.layer.addSublayer(layer)
    }
    avatarView.contentMode = .scaleAspectFill
    avatarView.clipsToBounds = true
    avatarView.layer.cornerRadius = Self.avatarSize / 2
    avatarView.backgroundColor = config.accentColor
    avatarView.tintColor = contrast(on: config.accentColor)
    avatarView.isAccessibilityElement = false
    avatarView.translatesAutoresizingMaskIntoConstraints = false
    avatarContainer.addSubview(avatarView)
    avatarContainer.translatesAutoresizingMaskIntoConstraints = false
    NSLayoutConstraint.activate([
      avatarContainer.widthAnchor.constraint(equalToConstant: Self.pulseSize),
      avatarContainer.heightAnchor.constraint(equalToConstant: Self.pulseSize),
      avatarView.widthAnchor.constraint(equalToConstant: Self.avatarSize),
      avatarView.heightAnchor.constraint(equalToConstant: Self.avatarSize),
      avatarView.centerXAnchor.constraint(equalTo: avatarContainer.centerXAnchor),
      avatarView.centerYAnchor.constraint(equalTo: avatarContainer.centerYAnchor),
    ])
    avatarRow.addArrangedSubview(avatarContainer)
    avatarRow.axis = .vertical
    avatarRow.alignment = .center

    // Answered: status · timer, transcript, actions.
    let dot = UILabel()
    style(dot, .subheadline, alpha: 0.6)
    dot.text = "·"
    dot.isAccessibilityElement = false
    let statusRow = UIStackView(arrangedSubviews: [statusLabel, dot, timerLabel])
    statusRow.axis = .horizontal
    statusRow.spacing = 8
    let statusWrapper = UIStackView(arrangedSubviews: [statusRow])
    statusWrapper.axis = .vertical
    statusWrapper.alignment = .center

    transcriptView.isEditable = false
    transcriptView.isSelectable = false
    transcriptView.backgroundColor = .clear
    transcriptView.textContainerInset = .zero
    transcriptView.textContainer.lineFragmentPadding = 0
    transcriptView.adjustsFontForContentSizeCategory = true
    transcriptView.setContentHuggingPriority(.defaultLow, for: .vertical)
    transcriptView.setContentCompressionResistancePriority(.defaultLow, for: .vertical)

    actionsStack.axis = .vertical
    actionsStack.spacing = 10

    activePanel.axis = .vertical
    activePanel.spacing = 16
    activePanel.addArrangedSubview(statusWrapper)
    activePanel.addArrangedSubview(transcriptView)
    activePanel.addArrangedSubview(actionsStack)
    activePanel.setContentHuggingPriority(.defaultLow, for: .vertical)

    spacer.setContentHuggingPriority(.defaultLow, for: .vertical)
    spacer.setContentCompressionResistancePriority(.defaultLow, for: .vertical)

    let decline: (column: UIView, button: UIButton)
    let answer: (column: UIView, button: UIButton)
    if config.answerBySwipe {
      swipeGroup.onBusyChanged = { [weak self] busy in
        guard let self else { return }
        // The circle must stay under the finger while a ringing button is touched.
        if busy {
          self.stopBreathing()
          self.swipeGroup.invite(false)
        } else if self.ringing {
          self.startBreathing()
          self.swipeGroup.invite(true)
        }
      }
      decline = swipeButton(
        symbol: "phone.down.fill", color: CallReminderConfig.declineColor, label: config.label(.decline),
        hint: config.label(.swipeToDecline)
      ) { [weak self] in self?.declineTapped() }
      answer = swipeButton(
        symbol: "phone.fill", color: CallReminderConfig.answerColor, label: config.label(.answer),
        hint: config.label(.swipeToAnswer)
      ) { [weak self] in self?.answerTapped() }
    } else {
      decline = roundButton(
        symbol: "phone.down.fill", color: CallReminderConfig.declineColor, label: config.label(.decline), size: 72,
        action: #selector(declineTapped))
      answer = roundButton(
        symbol: "phone.fill", color: CallReminderConfig.answerColor, label: config.label(.answer), size: 72,
        action: #selector(answerTapped))
    }
    answerButton = answer.button
    ringingControls.axis = .horizontal
    ringingControls.distribution = .fillEqually
    if config.answerBySwipe {
      // Natural-height columns, top-aligned: the circles stay level even when
      // one hint wraps to two lines.
      ringingControls.alignment = .top
    }
    ringingControls.addArrangedSubview(decline.column)
    ringingControls.addArrangedSubview(answer.column)

    let replay = roundButton(
      symbol: "arrow.counterclockwise", color: config.textColor.withAlphaComponent(0.16),
      label: config.label(.replay), size: 60, action: #selector(replayTapped))
    let end = roundButton(
      symbol: "phone.down.fill", color: CallReminderConfig.declineColor, label: config.label(.endCall), size: 60,
      action: #selector(endTapped))
    activeControls.axis = .horizontal
    activeControls.distribution = .fillEqually
    activeControls.addArrangedSubview(replay.column)
    activeControls.addArrangedSubview(end.column)

    [headerLabel, brandLabel, avatarRow, callerLabel, titleLabel, bodyLabel, activePanel, spacer, ringingControls,
     activeControls].forEach(content.addArrangedSubview)
    content.setCustomSpacing(16, after: avatarRow)
    content.setCustomSpacing(16, after: bodyLabel)
  }

  private func roundButton(
    symbol: String, color: UIColor, label: String, size: CGFloat, action: Selector
  ) -> (column: UIView, button: UIButton) {
    let button = UIButton(type: .system)
    styleCircle(button, symbol: symbol, color: color, label: label, size: size)
    button.addTarget(self, action: action, for: .touchUpInside)
    let caption = UILabel()
    style(caption, .footnote, alpha: 0.9)
    caption.text = label
    caption.isAccessibilityElement = false
    let column = UIStackView(arrangedSubviews: [button, caption])
    column.axis = .vertical
    column.alignment = .center
    column.spacing = 8
    return (column, button)
  }

  /**
   * Answer/Decline while ringing with `answerGesture: 'swipe'`: the circle is
   * dragged upwards (see `SwipeUpControl`), as the chevrons above it and the
   * hint below say. A tap only nudges it; VoiceOver/Switch Control activation
   * and the "Answer"/"Decline" custom actions commit directly.
   */
  private func swipeButton(
    symbol: String, color: UIColor, label: String, hint hintText: String, commit: @escaping () -> Void
  ) -> (column: UIView, button: UIButton) {
    let button = RingingCallButton(type: .system)
    styleCircle(button, symbol: symbol, color: color, label: label, size: 72)
    let chevron = chevronView()
    chevron.setContentHuggingPriority(.required, for: .vertical)
    let caption = UILabel()
    style(caption, .footnote, alpha: 0.9)
    caption.text = label
    caption.isAccessibilityElement = false
    let hint = UILabel()
    // Full-strength text faded with the view's alpha, which the gesture animates.
    style(hint, .caption1, lines: 2)
    hint.alpha = Self.swipeHintAlpha
    hint.text = hintText
    hint.isAccessibilityElement = false
    let column = UIStackView(arrangedSubviews: [chevron, button, caption, hint])
    column.axis = .vertical
    column.alignment = .center
    column.spacing = 8
    column.setCustomSpacing(6, after: chevron)
    column.setCustomSpacing(2, after: caption)
    hint.widthAnchor.constraint(lessThanOrEqualTo: column.widthAnchor, constant: -8).isActive = true
    swipeControls.append(
      SwipeUpControl(
        button: button, chevron: chevron, hint: hint, group: swipeGroup, hintAlpha: Self.swipeHintAlpha,
        label: label, onCommit: commit))
    return (column, button)
  }

  /// Two stacked upward chevrons: the "swipe up" hint above a ringing button.
  private func chevronView() -> UIView {
    let symbol = UIImage(
      systemName: "chevron.up", withConfiguration: UIImage.SymbolConfiguration(pointSize: 13, weight: .bold))
    let top = UIImageView(image: symbol)
    let bottom = UIImageView(image: symbol)
    bottom.alpha = 0.5
    let stack = UIStackView(arrangedSubviews: [top, bottom])
    stack.axis = .vertical
    stack.alignment = .center
    stack.spacing = -4
    stack.tintColor = config.textColor
    stack.isAccessibilityElement = false
    stack.accessibilityElementsHidden = true
    return stack
  }

  private func styleCircle(_ button: UIButton, symbol: String, color: UIColor, label: String, size: CGFloat) {
    button.backgroundColor = color
    button.layer.cornerRadius = size / 2
    button.tintColor = contrast(on: color)
    button.setImage(
      UIImage(systemName: symbol, withConfiguration: UIImage.SymbolConfiguration(pointSize: size * 0.36, weight: .semibold)),
      for: .normal)
    button.accessibilityLabel = label
    button.translatesAutoresizingMaskIntoConstraints = false
    NSLayoutConstraint.activate([
      button.widthAnchor.constraint(equalToConstant: size),
      button.heightAnchor.constraint(equalToConstant: size),
    ])
  }

  private func bindActions(_ actions: [CallAction]) {
    guard boundActions != actions else { return }
    boundActions = actions
    actionsStack.arrangedSubviews.forEach { $0.removeFromSuperview() }
    actions.forEach { actionsStack.addArrangedSubview(actionButton($0)) }
  }

  private func actionButton(_ action: CallAction) -> UIButton {
    let fill: UIColor
    switch action.style {
    case CallAction.stylePrimary: fill = config.accentColor
    case CallAction.styleDestructive: fill = CallReminderConfig.declineColor
    default: fill = config.textColor.withAlphaComponent(0.16)
    }
    var configuration = UIButton.Configuration.filled()
    configuration.title = action.label
    configuration.cornerStyle = .capsule
    configuration.baseBackgroundColor = fill
    configuration.baseForegroundColor =
      action.style == CallAction.styleSecondary ? config.textColor : contrast(on: fill)
    configuration.contentInsets = NSDirectionalEdgeInsets(top: 14, leading: 20, bottom: 14, trailing: 20)
    configuration.titleTextAttributesTransformer = UIConfigurationTextAttributesTransformer { incoming in
      var outgoing = incoming
      outgoing.font = UIFont.preferredFont(forTextStyle: .headline)
      return outgoing
    }
    let actionId = action.id
    let button = UIButton(
      configuration: configuration,
      primaryAction: UIAction { [weak self] _ in
        guard let self, let callId = self.callId else { return }
        self.core.performAction(callId, actionId: actionId)
      })
    button.heightAnchor.constraint(greaterThanOrEqualToConstant: 52).isActive = true
    return button
  }

  private func setControlsEnabled(_ enabled: Bool) {
    for stack in [ringingControls, activeControls, actionsStack] {
      stack.isUserInteractionEnabled = enabled
      stack.alpha = enabled ? 1 : 0.5
    }
  }

  private func setTranscript(highlight range: NSRange?) {
    let paragraph = NSMutableParagraphStyle()
    paragraph.lineSpacing = 6
    let text = NSMutableAttributedString(
      string: transcriptText,
      attributes: [
        .font: UIFont.preferredFont(forTextStyle: .title3),
        .foregroundColor: config.textColor,
        .paragraphStyle: paragraph,
      ])
    if let range {
      text.addAttribute(.backgroundColor, value: config.accentColor.withAlphaComponent(0.4), range: range)
    }
    transcriptView.attributedText = text
  }

  // MARK: - Timer & animations

  private func startTimer() {
    stopTimer()
    updateTimer()
    let timer = Timer(timeInterval: 1, repeats: true) { [weak self] _ in self?.updateTimer() }
    RunLoop.main.add(timer, forMode: .common)
    self.timer = timer
  }

  private func stopTimer() {
    timer?.invalidate()
    timer = nil
  }

  private func updateTimer() {
    guard let answeredAt else { return }
    let elapsed = max(Int(Date().timeIntervalSince(answeredAt)), 0)
    timerLabel.text = String(format: "%d:%02d", elapsed / 60, elapsed % 60)
    timerLabel.accessibilityLabel = DateComponentsFormatter.localizedString(
      from: DateComponents(second: elapsed), unitsStyle: .full)
  }

  private func startRingingAnimations() {
    guard !UIAccessibility.isReduceMotionEnabled else { return }
    for (index, layer) in pulseLayers.enumerated() where layer.animation(forKey: "pulse") == nil {
      let scale = CABasicAnimation(keyPath: "transform.scale")
      scale.fromValue = 0.64
      scale.toValue = 1
      let fade = CABasicAnimation(keyPath: "opacity")
      fade.fromValue = 0.45
      fade.toValue = 0
      let group = CAAnimationGroup()
      group.animations = [scale, fade]
      group.duration = 1.6
      group.repeatCount = .infinity
      group.beginTime = layer.convertTime(CACurrentMediaTime(), from: nil) + Double(index) * 0.8
      layer.add(group, forKey: "pulse")
    }
    startBreathing()
    if config.answerBySwipe { swipeGroup.invite(true) }
  }

  /// The Answer circle "breathes" in place (grow, settle, rest) — scaled about
  /// its own centre so it stays level with Decline. Paused while a ringing
  /// button is touched, so the circle stays under the finger.
  private func startBreathing() {
    guard !UIAccessibility.isReduceMotionEnabled, let answerButton,
          answerButton.layer.animation(forKey: "nudge") == nil
    else { return }
    let breathe = CAKeyframeAnimation(keyPath: "transform.scale")
    breathe.values = [1, Self.nudgeScale, 1, 1]
    breathe.keyTimes = [0, 0.33, 0.66, 1]
    breathe.timingFunctions = Array(repeating: CAMediaTimingFunction(name: .easeInEaseOut), count: 3)
    breathe.duration = 1.2
    breathe.repeatCount = .infinity
    breathe.beginTime = answerButton.layer.convertTime(CACurrentMediaTime(), from: nil) + 0.4
    answerButton.layer.add(breathe, forKey: "nudge")
  }

  private func stopBreathing() {
    answerButton?.layer.removeAnimation(forKey: "nudge")
  }

  private func stopRingingAnimations() {
    ringing = false
    pulseLayers.forEach { $0.removeAnimation(forKey: "pulse") }
    stopBreathing()
    // Abandons a drag in progress (answered from the notification, timed out, …).
    swipeGroup.reset()
  }

  // MARK: - Helpers

  private func style(
    _ label: UILabel, _ textStyle: UIFont.TextStyle, alpha: CGFloat = 1, weight: UIFont.Weight = .regular, lines: Int = 1
  ) {
    // Scale from the default-size font so Dynamic Type is applied exactly once.
    label.font = UIFontMetrics(forTextStyle: textStyle)
      .scaledFont(for: .systemFont(ofSize: Self.baseSize(textStyle), weight: weight))
    label.adjustsFontForContentSizeCategory = true
    label.textColor = config.textColor.withAlphaComponent(alpha)
    label.textAlignment = .center
    label.numberOfLines = lines
    label.lineBreakMode = .byTruncatingTail
  }

  /// Point size of a text style at the default content size category.
  private static func baseSize(_ textStyle: UIFont.TextStyle) -> CGFloat {
    UIFontDescriptor.preferredFontDescriptor(
      withTextStyle: textStyle, compatibleWith: UITraitCollection(preferredContentSizeCategory: .large)
    ).pointSize
  }

  private func avatarImage(for call: IncomingCall) -> UIImage? {
    if let uri = call.avatarUri {
      if let url = URL(string: uri), url.isFileURL, let image = UIImage(contentsOfFile: url.path) {
        return image
      }
      if let image = UIImage(named: uri) {
        return image
      }
    }
    if let name = config.largeIcon, let image = UIImage(named: name) {
      return image
    }
    return Self.appIcon() ?? UIImage(systemName: "bell.fill")
  }

  private static func appIcon() -> UIImage? {
    guard let icons = Bundle.main.infoDictionary?["CFBundleIcons"] as? [String: Any],
          let primary = icons["CFBundlePrimaryIcon"] as? [String: Any],
          let files = primary["CFBundleIconFiles"] as? [String],
          let name = files.last
    else { return nil }
    return UIImage(named: name)
  }

  private func contrast(on color: UIColor) -> UIColor {
    var red: CGFloat = 0
    var green: CGFloat = 0
    var blue: CGFloat = 0
    var alpha: CGFloat = 0
    guard color.getRed(&red, green: &green, blue: &blue, alpha: &alpha) else { return .white }
    // Translucent fills sit on the screen background: use the text colour.
    guard alpha > 0.5 else { return config.textColor }
    let luminance = 0.2126 * red + 0.7152 * green + 0.0722 * blue
    return luminance > 0.6 ? .black : .white
  }
}

// MARK: - Swipe to answer / decline

/// A ringing Answer/Decline circle. VoiceOver and Switch Control users cannot
/// drag, so activating the element (double-tap) commits directly.
private final class RingingCallButton: UIButton {
  var onAccessibilityActivate: (() -> Void)?

  override func accessibilityActivate() -> Bool {
    guard let onAccessibilityActivate else { return super.accessibilityActivate() }
    onAccessibilityActivate()
    return true
  }
}

/**
 * "Swipe up to answer / decline" for one ringing button: the circle follows
 * the finger 1:1 up to the commit point, then with increasing resistance, and
 * commits when released past `commitFraction` of the drag distance (or on a
 * fast upward fling); otherwise it springs back. Past the commit point it grows
 * slightly (with a selection tick) so the user knows letting go will act. While
 * idle, the chevron above it floats upwards to invite the gesture. A plain tap never commits (no pocket answers) — it bounces
 * the circle and emphasises the hint instead. Accessibility activation and the
 * custom action commit directly. Buttons of one `Group` are exclusive: while
 * one is dragged the others do not start, and only one finger is tracked.
 */
private final class SwipeUpControl: NSObject, UIGestureRecognizerDelegate {
  /// Shared by the buttons of one screen: at most one is dragged at a time.
  final class Group {
    var members: [SwipeUpControl] = []
    weak var owner: SwipeUpControl?
    /// True while any member is dragged or settling (e.g. to pause the breathing animation).
    var onBusyChanged: ((Bool) -> Void)?
    private var busy = false

    func updateBusy() {
      let now = owner != nil || members.contains { $0.settling }
      guard now != busy else { return }
      busy = now
      onBusyChanged?(now)
    }

    /// Puts every member back at rest, abandoning any drag (e.g. the call stopped ringing).
    func reset() {
      owner = nil
      members.forEach { $0.resetToRest() }
      updateBusy()
    }

    /// Starts or stops the chevrons' idle "float up" cue on every member.
    func invite(_ on: Bool) {
      members.forEach { on ? $0.startInvite() : $0.stopInvite() }
    }
  }

  private static let maxDistance: CGFloat = 140
  private static let minDistance: CGFloat = 90
  private static let commitFraction: CGFloat = 0.4
  private static let flingVelocity: CGFloat = 1000
  private static let flingMinTravel: CGFloat = 24
  private static let hop: CGFloat = 26
  private static let armedScale: CGFloat = 1.12
  private static let inviteRise: CGFloat = 8

  let button: RingingCallButton
  private let chevron: UIView
  private let hint: UIView
  private weak var group: Group?
  private let hintAlpha: CGFloat
  private let onCommit: () -> Void
  private let pan = UIPanGestureRecognizer()
  private(set) var settling = false
  private var settleToken = 0
  private var emphasisToken = 0
  private var armed = false
  private var distance = SwipeUpControl.maxDistance
  private var selection: UISelectionFeedbackGenerator?

  init(
    button: RingingCallButton, chevron: UIView, hint: UIView, group: Group, hintAlpha: CGFloat, label: String,
    onCommit: @escaping () -> Void
  ) {
    self.button = button
    self.chevron = chevron
    self.hint = hint
    self.group = group
    self.hintAlpha = hintAlpha
    self.onCommit = onCommit
    super.init()
    group.members.append(self)
    pan.addTarget(self, action: #selector(handlePan(_:)))
    pan.maximumNumberOfTouches = 1
    pan.delegate = self
    button.addGestureRecognizer(pan)
    // One finger on one button: no second simultaneous touch anywhere else.
    button.isExclusiveTouch = true
    button.addTarget(self, action: #selector(tapped), for: .touchUpInside)
    button.onAccessibilityActivate = { [weak self] in self?.commit() }
    button.accessibilityCustomActions = [
      UIAccessibilityCustomAction(name: label) { [weak self] _ in
        self?.commit()
        return true
      },
    ]
  }

  /// Commits as if dragged: haptic confirmation, then `onCommit`; the circle settles back.
  func commit() {
    UIImpactFeedbackGenerator(style: .medium).impactOccurred()
    onCommit()
    // The screen normally switches state right away; if the call keeps ringing
    // it is back at rest.
    springBack()
  }

  func resetToRest() {
    settleToken += 1
    emphasisToken += 1
    settling = false
    armed = false
    selection = nil
    [button, chevron, hint].forEach { $0.layer.removeAllAnimations() }
    button.transform = .identity
    button.alpha = 1
    chevron.transform = .identity
    chevron.alpha = 1
    hint.transform = .identity
    hint.alpha = hintAlpha
  }

  // MARK: Gesture

  func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
    guard gestureRecognizer === pan else { return true }
    if let owner = group?.owner, owner !== self { return false }
    // Upward only; a sideways or downward move stays a (non-committing) touch.
    let translation = pan.translation(in: button.superview)
    return translation.y < 0 && abs(translation.y) >= abs(translation.x)
  }

  @objc private func handlePan(_ recognizer: UIPanGestureRecognizer) {
    let pulled = max(0, -recognizer.translation(in: button.superview).y)
    switch recognizer.state {
    case .began:
      group?.owner = self
      settleToken += 1
      settling = false
      calmHint()
      // Freeze any in-flight settle animation where it is.
      button.layer.removeAllAnimations()
      armed = false
      let height = button.window?.bounds.height ?? 0
      distance = height > 0 ? min(Self.maxDistance, max(Self.minDistance, height / 4)) : Self.maxDistance
      selection = UISelectionFeedbackGenerator()
      selection?.prepare()
      group?.updateBusy()
      follow(pulled)
    case .changed:
      guard group?.owner === self else { return }
      follow(pulled)
    case .ended:
      guard group?.owner === self else { return }
      let velocity = recognizer.velocity(in: button.superview).y
      finish(
        commit: pulled >= distance * Self.commitFraction
          || (velocity <= -Self.flingVelocity && pulled >= Self.flingMinTravel))
    case .cancelled, .failed:
      guard group?.owner === self else { return }
      finish(commit: false)
    default:
      break
    }
  }

  /// 1:1 up to the commit point, then a rubber band approaching `distance` asymptotically.
  private func follow(_ pulled: CGFloat) {
    let commitAt = distance * Self.commitFraction
    let slack = distance - commitAt
    let shown = pulled <= commitAt ? pulled : commitAt + slack * (1 - exp(-(pulled - commitAt) / slack))
    let scale = armed ? Self.armedScale : 1
    button.transform = CGAffineTransform(translationX: 0, y: -shown).scaledBy(x: scale, y: scale)
    let progress = min(1, pulled / (distance * Self.commitFraction))
    chevron.alpha = 1 - progress
    hint.alpha = hintAlpha * (1 - 0.5 * progress)
    group?.members.forEach { if $0 !== self { $0.button.alpha = 1 - 0.5 * progress } }
    let nowArmed = pulled >= distance * Self.commitFraction
    if nowArmed != armed {
      armed = nowArmed
      if nowArmed {
        selection?.selectionChanged()
        selection?.prepare()
      }
      let scale = nowArmed ? Self.armedScale : 1
      UIView.animate(
        withDuration: 0.14, delay: 0, options: [.allowUserInteraction, .beginFromCurrentState, .curveEaseOut],
        animations: {
          self.button.transform = CGAffineTransform(translationX: 0, y: -shown).scaledBy(x: scale, y: scale)
        })
    }
  }

  private func finish(commit shouldCommit: Bool) {
    group?.owner = nil
    armed = false
    selection = nil
    if shouldCommit {
      commit()
    } else {
      springBack()
    }
    group?.updateBusy()
  }

  /// A plain tap: bounce and point at the hint. Under VoiceOver / Switch Control
  /// a tap that reaches us is an activation, so it commits.
  @objc private func tapped() {
    if UIAccessibility.isVoiceOverRunning || UIAccessibility.isSwitchControlRunning {
      commit()
      return
    }
    bounce()
  }

  // MARK: Animations

  private func springBack() {
    settleToken += 1
    let token = settleToken
    settling = true
    let others = group?.members.filter { $0 !== self } ?? []
    UIView.animate(
      withDuration: 0.45, delay: 0, usingSpringWithDamping: UIAccessibility.isReduceMotionEnabled ? 1 : 0.62,
      initialSpringVelocity: 0,
      options: [.allowUserInteraction, .beginFromCurrentState],
      animations: {
        self.button.transform = .identity
        self.chevron.alpha = 1
        self.hint.alpha = self.hintAlpha
        others.forEach { $0.button.alpha = 1 }
      },
      completion: { [weak self] _ in self?.settled(token) })
  }

  private func bounce() {
    settleToken += 1
    let token = settleToken
    settling = true
    group?.updateBusy()
    emphasizeHint()
    guard !UIAccessibility.isReduceMotionEnabled else {
      settled(token)
      return
    }
    UIView.animateKeyframes(
      withDuration: 0.7, delay: 0, options: [.allowUserInteraction, .calculationModeCubic],
      animations: {
        UIView.addKeyframe(withRelativeStartTime: 0, relativeDuration: 0.3) {
          self.button.transform = CGAffineTransform(translationX: 0, y: -Self.hop)
        }
        UIView.addKeyframe(withRelativeStartTime: 0.3, relativeDuration: 0.3) {
          self.button.transform = .identity
        }
        UIView.addKeyframe(withRelativeStartTime: 0.6, relativeDuration: 0.2) {
          self.button.transform = CGAffineTransform(translationX: 0, y: -Self.hop * 0.35)
        }
        UIView.addKeyframe(withRelativeStartTime: 0.8, relativeDuration: 0.2) {
          self.button.transform = .identity
        }
      },
      completion: { [weak self] _ in self?.settled(token) })
  }

  private func settled(_ token: Int) {
    // A newer touch or animation took over.
    guard token == settleToken else { return }
    settling = false
    group?.updateBusy()
  }

  private func emphasizeHint() {
    emphasisToken += 1
    let token = emphasisToken
    let reduceMotion = UIAccessibility.isReduceMotionEnabled
    UIView.animate(
      withDuration: 0.18, delay: 0, options: [.allowUserInteraction, .beginFromCurrentState],
      animations: {
        self.hint.alpha = 1
        if !reduceMotion {
          self.hint.transform = CGAffineTransform(scaleX: 1.08, y: 1.08)
        }
      },
      completion: { [weak self] _ in
        guard let self, token == self.emphasisToken else { return }
        UIView.animate(
          withDuration: 0.32, delay: 1.4, options: [.allowUserInteraction, .beginFromCurrentState],
          animations: {
            self.hint.alpha = self.hintAlpha
            self.hint.transform = .identity
          })
      })
  }

  private func calmHint() {
    emphasisToken += 1
    hint.layer.removeAllAnimations()
    chevron.layer.removeAllAnimations()
    hint.transform = .identity
    hint.alpha = hintAlpha
    chevron.transform = .identity
  }

  /// Floats the chevron up while fading it, then rests — repeated until touched or stopped.
  /// A layer animation: it goes away with the view, nothing to tear down.
  func startInvite() {
    guard !UIAccessibility.isReduceMotionEnabled, chevron.layer.animation(forKey: "invite") == nil else { return }
    let rise = CAKeyframeAnimation(keyPath: "transform.translation.y")
    rise.values = [0, -Self.inviteRise * 0.6, -Self.inviteRise]
    let fade = CAKeyframeAnimation(keyPath: "opacity")
    fade.values = [1, 1, 0]
    let invite = CAAnimationGroup()
    invite.animations = [rise, fade]
    invite.duration = 1.1
    invite.repeatCount = .infinity
    invite.timingFunction = CAMediaTimingFunction(name: .easeInEaseOut)
    invite.beginTime = chevron.layer.convertTime(CACurrentMediaTime(), from: nil) + 0.3
    chevron.layer.add(invite, forKey: "invite")
  }

  func stopInvite() {
    chevron.layer.removeAnimation(forKey: "invite")
  }
}
