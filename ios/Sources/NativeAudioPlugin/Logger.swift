import Foundation
import os.log

public class Logger {
    private var osLogger: OSLog?

    public static var debugModeEnabled = false

    public init(logTag: String) {
        osLogger = OSLog(subsystem: Bundle.main.bundleIdentifier ?? "com.capgo.native-audio", category: logTag)
    }

    func error(_ message: String, _ args: CVarArg...) {
        osLog(message, level: .error, args)
    }

    func warning(_ message: String, _ args: CVarArg...) {
        osLog(message, level: .fault, args)
    }

    func info(_ message: String, _ args: CVarArg...) {
        osLog(message, level: .info, args)
    }

    func debug(_ message: String, _ args: CVarArg...) {
        osLog(message, level: .debug, args)
    }

    func verbose(_ message: String, _ args: CVarArg...) {
        osLog(message, level: .default, args)
    }

    private func osLog(_ message: String, level: OSLogType = .default, _ args: [CVarArg]) {
        if !Logger.debugModeEnabled {
            return
        }
        let formatted = String(format: message, arguments: args)
        guard let osLogger else {
            return
        }
        os_log("%{public}@", log: osLogger, type: level, formatted)
    }
}
