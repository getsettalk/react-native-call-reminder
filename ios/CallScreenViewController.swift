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

  private static let avatarSize: CGFloat = 104
  private static let pulseSize: CGFloat = 168
  private static let endedLinger: TimeInterval = 1.2
  private static let minHeightForAvatar: CGFloat = 560

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

    let decline = roundButton(
      symbol: "phone.down.fill", color: CallReminderConfig.declineColor, label: config.label(.decline), size: 72,
      action: #selector(declineTapped))
    let answer = roundButton(
      symbol: "phone.fill", color: CallReminderConfig.answerColor, label: config.label(.answer), size: 72,
      action: #selector(answerTapped))
    answerButton = answer.button
    ringingControls.axis = .horizontal
    ringingControls.distribution = .fillEqually
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
    button.backgroundColor = color
    button.layer.cornerRadius = size / 2
    button.tintColor = contrast(on: color)
    button.setImage(
      UIImage(systemName: symbol, withConfiguration: UIImage.SymbolConfiguration(pointSize: size * 0.36, weight: .semibold)),
      for: .normal)
    button.accessibilityLabel = label
    button.addTarget(self, action: action, for: .touchUpInside)
    button.translatesAutoresizingMaskIntoConstraints = false
    NSLayoutConstraint.activate([
      button.widthAnchor.constraint(equalToConstant: size),
      button.heightAnchor.constraint(equalToConstant: size),
    ])
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
    if let answerButton, answerButton.layer.animation(forKey: "nudge") == nil {
      let nudge = CABasicAnimation(keyPath: "transform.translation.y")
      nudge.fromValue = 0
      nudge.toValue = -10
      nudge.duration = 0.45
      nudge.autoreverses = true
      nudge.repeatCount = .infinity
      nudge.timingFunction = CAMediaTimingFunction(name: .easeInEaseOut)
      answerButton.layer.add(nudge, forKey: "nudge")
    }
  }

  private func stopRingingAnimations() {
    pulseLayers.forEach { $0.removeAnimation(forKey: "pulse") }
    answerButton?.layer.removeAnimation(forKey: "nudge")
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
