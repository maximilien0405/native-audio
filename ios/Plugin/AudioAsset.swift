//
//  AudioAsset.swift
//  Plugin
//
//  Created by priyank on 2020-05-29.
//  Copyright © 2022 Martin Donadieu. All rights reserved.
//

import AVFoundation

public class AudioAsset: NSObject, AVAudioPlayerDelegate {

    var channels: [AVAudioPlayer] = []
    var playIndex: Int = 0
    var assetId: String = ""
    var initialVolume: Float = 1.0
    var fadeDelay: Float = 1.0
    var owner: NativeAudio

    let FADESTEP: Float = 0.05
    let FADEDELAY: Float = 0.08

    private var currentTimeTimer: Timer?

    init(owner: NativeAudio, withAssetId assetId: String, withPath path: String!, withChannels channels: Int!, withVolume volume: Float!, withFadeDelay delay: Float!) {

        self.owner = owner
        self.assetId = assetId
        self.channels = []
        self.initialVolume = volume ?? 1.0

        super.init()

        let pathUrl: URL = URL(string: path.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed)!)!
        
        owner.executeOnAudioQueue { [self] in
            for _ in 0..<channels {
                do {
                    let player: AVAudioPlayer! = try AVAudioPlayer(contentsOf: pathUrl)
                    player.delegate = self
                    if player != nil {
                        player.enableRate = true
                        player.volume = volume
                        player.rate = 1.0
                        player.prepareToPlay()
                        self.channels.append(player)
                    }
                } catch let error as NSError {
                    print(error.debugDescription)
                    print("Error loading \(String(describing: path))")
                }
            }
        }
    }

    func getCurrentTime() -> TimeInterval {
        var result: TimeInterval = 0
        owner.executeOnAudioQueue { [self] in
            if channels.count != 1 {
                result = 0
                return
            }
            let player: AVAudioPlayer = channels[playIndex]
            result = player.currentTime
        }
        return result
    }

    func setCurrentTime(time: TimeInterval) {
        owner.executeOnAudioQueue { [self] in
            if channels.count != 1 {
                return
            }
            let player: AVAudioPlayer = channels[playIndex]
            player.currentTime = time
        }
    }

    func getDuration() -> TimeInterval {
        var result: TimeInterval = 0
        owner.executeOnAudioQueue { [self] in
            if channels.count != 1 {
                result = 0
                return
            }
            let player: AVAudioPlayer = channels[playIndex]
            result = player.duration
        }
        return result
    }

    func play(time: TimeInterval, delay: TimeInterval) {
        owner.executeOnAudioQueue { [self] in
            guard !channels.isEmpty else {
                NSLog("No channels available")
                return
            }
            guard playIndex < channels.count else {
                NSLog("PlayIndex out of bounds")
                playIndex = 0
                return
            }
            
            let player = channels[playIndex]
            player.currentTime = time
            player.numberOfLoops = 0
            if delay > 0 {
                player.play(atTime: player.deviceCurrentTime + delay)
            } else {
                player.play()
            }
            playIndex += 1
            playIndex = playIndex % channels.count
            NSLog("About to start timer updates")
            startCurrentTimeUpdates()
        }
    }

    func playWithFade(time: TimeInterval) {
        owner.executeOnAudioQueue { [self] in
            guard !channels.isEmpty else {
                NSLog("No channels available")
                return
            }
            guard playIndex < channels.count else {
                NSLog("PlayIndex out of bounds")
                playIndex = 0
                return
            }
            
            let player: AVAudioPlayer = channels[playIndex]
            player.currentTime = time

            if !player.isPlaying {
                player.numberOfLoops = 0
                player.volume = initialVolume
                player.play()
                playIndex += 1
                playIndex = playIndex % channels.count
                NSLog("PlayWithFade: About to start timer updates")
                startCurrentTimeUpdates()
            } else {
                if player.volume < initialVolume {
                    player.volume += self.FADESTEP
                }
            }
        }
    }

    func pause() {
        owner.executeOnAudioQueue { [self] in
            stopCurrentTimeUpdates()
            let player: AVAudioPlayer = channels[playIndex]
            player.pause()
        }
    }

    func resume() {
        owner.executeOnAudioQueue { [self] in
            let player: AVAudioPlayer = channels[playIndex]
            let timeOffset = player.deviceCurrentTime + 0.01
            player.play(atTime: timeOffset)
            NSLog("Resume: About to start timer updates")
            startCurrentTimeUpdates()
        }
    }

    func stop() {
        owner.executeOnAudioQueue { [self] in
            stopCurrentTimeUpdates()
            for player in channels {
                if player.isPlaying {
                    player.stop()
                }
                player.currentTime = 0
                player.numberOfLoops = 0
            }
            playIndex = 0
        }
    }

    func stopWithFade() {
        owner.executeOnAudioQueue { [self] in
            let player: AVAudioPlayer = channels[playIndex]
            if player.isPlaying {
                if player.volume > self.FADESTEP {
                    player.volume -= self.FADESTEP
                    // Schedule next fade step
                    DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(Int(self.FADEDELAY * 1000))) { [weak self] in
                        self?.stopWithFade()
                    }
                } else {
                    // Volume is near 0, actually stop
                    player.volume = 0
                    self.stop()
                }
            }
        }
    }

    func loop() {
        owner.executeOnAudioQueue { [self] in
            self.stop()
            let player: AVAudioPlayer = channels[playIndex]
            player.delegate = self
            player.numberOfLoops = -1
            player.play()
            playIndex += 1
            playIndex = playIndex % channels.count
            NSLog("Loop: About to start timer updates")
            startCurrentTimeUpdates()
        }
    }

    func unload() {
        owner.executeOnAudioQueue { [self] in
            self.stop()
            channels = []
        }
    }

    func setVolume(volume: NSNumber!) {
        owner.executeOnAudioQueue { [self] in
            for player in channels {
                player.volume = volume.floatValue
            }
        }
    }

    func setRate(rate: NSNumber!) {
        owner.executeOnAudioQueue { [self] in
            for player in channels {
                player.rate = rate.floatValue
            }
        }
    }

    public func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        owner.executeOnAudioQueue { [self] in
            NSLog("playerDidFinish")
            self.owner.notifyListeners("complete", data: [
                "assetId": self.assetId
            ])
        }
    }

    func playerDecodeError(player: AVAudioPlayer!, error: NSError!) {

    }

    func isPlaying() -> Bool {
        var result: Bool = false
        owner.executeOnAudioQueue { [self] in
            if channels.count != 1 {
                result = false
                return
            }
            let player: AVAudioPlayer = channels[playIndex]
            result = player.isPlaying
        }
        return result
    }

    internal func startCurrentTimeUpdates() {
        NSLog("Starting timer updates")
        DispatchQueue.main.async { [weak self] in
            guard let self = self else {
                NSLog("Self is nil in timer start")
                return 
            }
            
            self.currentTimeTimer?.invalidate()
            self.currentTimeTimer = Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { [weak self] _ in
                guard let self = self else {
                    NSLog("Self is nil in timer callback")
                    return 
                }
                
                var shouldNotify = false
                var currentTime: TimeInterval = 0
                
                self.owner.executeOnAudioQueue {
                    shouldNotify = self.isPlaying()
                    NSLog("isPlaying returned: \(shouldNotify)")
                    if shouldNotify {
                        currentTime = self.getCurrentTime()
                        NSLog("getCurrentTime returned: \(currentTime)")
                    }
                }

                if shouldNotify {
                    NSLog("Calling notifyCurrentTime")
                    self.owner.notifyCurrentTime(self)
                } else {
                    NSLog("Stopping timer - not playing")
                    self.stopCurrentTimeUpdates()
                }
            }
            RunLoop.current.add(self.currentTimeTimer!, forMode: .common)
            NSLog("Timer added to RunLoop")
        }
    }

    internal func stopCurrentTimeUpdates() {
        DispatchQueue.main.async { [weak self] in
            self?.currentTimeTimer?.invalidate()
            self?.currentTimeTimer = nil
        }
    }
}
