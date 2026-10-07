require "json"

package = JSON.parse(File.read(File.join(__dir__, "package.json")))

Pod::Spec.new do |s|
  s.name         = "react-native-call-reminder"
  s.version      = package["version"]
  s.summary      = package["description"]
  s.homepage     = package["homepage"]
  s.license      = package["license"]
  s.authors      = package["author"]

  s.platforms    = { :ios => min_ios_version_supported }
  s.source       = { :git => package["repository"]["url"].sub(/^git\+/, ""), :tag => "v#{s.version}" }

  s.source_files = "ios/**/*.{h,m,mm,swift}"
  # Required-reason API declarations (UserDefaults), merged into the app's privacy report.
  s.resource_bundles = { "react-native-call-reminder_privacy" => ["ios/PrivacyInfo.xcprivacy"] }
  s.swift_version = "5.9"
  # LocalAuthentication: getDiagnostics().keyguardSecure (whether a passcode is set; never prompts).
  s.frameworks = "UIKit", "UserNotifications", "AVFoundation", "LocalAuthentication"

  # The ObjC++ module imports the generated Swift interface (react_native_call_reminder-Swift.h).
  s.pod_target_xcconfig = {
    "DEFINES_MODULE" => "YES"
  }

  # Codegen, React-Core and the New Architecture headers (keeps the xcconfig above).
  install_modules_dependencies(s)
end
