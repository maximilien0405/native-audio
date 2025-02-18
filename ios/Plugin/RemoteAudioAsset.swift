import AVFoundation

public class RemoteAudioAsset: AudioAsset {
    var playerItems: [AVPlayerItem] = []
    var players: [AVPlayer] = []
    var playerObservers: [NSKeyValueObservation] = []
    var duration: TimeInterval = 0
    var asset: AVURLAsset?

    override init(owner: NativeAudio, withAssetId assetId: String, withPath path: String!, withChannels channels: Int!, withVolume volume: Float!, withFadeDelay delay: Float!) {
        super.init(owner: owner, withAssetId: assetId, withPath: path, withChannels: channels ?? 1, withVolume: volume ?? 1.0, withFadeDelay: delay ?? 0.0)

        owner.executeOnAudioQueue { [self] in
            if let url = URL(string: path) {
                let asset = AVURLAsset(url: url, options: [AVURLAssetPreferPreciseDurationAndTimingKey: true])
                self.asset = asset

                for _ in 0..<(channels ?? 1) {
                    let playerItem = AVPlayerItem(asset: asset)
                    let player = AVPlayer(playerItem: playerItem)
                    player.volume = volume ?? 1.0
                    player.rate = 1.0
                    self.playerItems.append(playerItem)
                    self.players.append(player)

                    // Add observer for duration
                    let durationObserver = playerItem.observe(\.status) { [weak self] item, _ in
                        self?.owner.executeOnAudioQueue { [self] in
                            if item.status == .readyToPlay {
                                self?.duration = item.duration.seconds
                            }
                        }
                    }
                    self.playerObservers.append(durationObserver)

                    // Add observer for playback finished
                    let observer = player.observe(\.timeControlStatus) { [weak self] player, _ in
                        self?.owner.executeOnAudioQueue { [self] in
                            if player.timeControlStatus == .paused && player.currentItem?.currentTime() == player.currentItem?.duration {
                                self?.playerDidFinishPlaying(player: player)
                            }
                        }
                    }
                    self.playerObservers.append(observer)
                }
            }
        }
    }

    func playerDidFinishPlaying(player: AVPlayer) {
        owner.executeOnAudioQueue { [self] in
            self.owner.notifyListeners("complete", data: [
                "assetId": self.assetId
            ])
        }
    }

    override func play(time: TimeInterval, delay: TimeInterval) {
        owner.executeOnAudioQueue { [self] in
            guard !players.isEmpty else { return }
            let player = players[playIndex]
            if delay > 0 {
                // Convert delay to CMTime and add to current time
                let currentTime = player.currentTime()
                let delayTime = CMTimeMakeWithSeconds(delay, preferredTimescale: currentTime.timescale)
                let timeToPlay = CMTimeAdd(currentTime, delayTime)
                player.seek(to: timeToPlay)
            } else {
                player.seek(to: CMTimeMakeWithSeconds(time, preferredTimescale: 1))
            }
            player.play()
            playIndex = (playIndex + 1) % players.count
            NSLog("RemoteAudioAsset: About to start timer updates")
            startCurrentTimeUpdates()
        }
    }

    override func pause() {
        owner.executeOnAudioQueue { [self] in
            guard !players.isEmpty else { return }
            let player = players[playIndex]
            player.pause()
            stopCurrentTimeUpdates()
        }
    }

    override func resume() {
        owner.executeOnAudioQueue { [self] in
            guard !players.isEmpty else { return }
            let player = players[playIndex]
            player.play()
            NSLog("RemoteAudioAsset Resume: About to start timer updates")
            startCurrentTimeUpdates()  // Add timer start
        }
    }

    override func stop() {
        owner.executeOnAudioQueue { [self] in
            stopCurrentTimeUpdates()  // Stop timer first
            for player in players {
                // First pause
                player.pause()
                // Then reset to beginning
                player.seek(to: .zero, completionHandler: { _ in
                    // Reset any loop settings
                    player.actionAtItemEnd = .pause
                })
            }
            // Reset playback state
            playIndex = 0
        }
    }

    override func loop() {
        owner.executeOnAudioQueue { [self] in
            for player in players {
                player.actionAtItemEnd = .none
                NotificationCenter.default.removeObserver(self,
                                                      name: .AVPlayerItemDidPlayToEndTime,
                                                      object: player.currentItem)
                NotificationCenter.default.addObserver(self,
                                                   selector: #selector(playerItemDidReachEnd(notification:)),
                                                   name: .AVPlayerItemDidPlayToEndTime,
                                                   object: player.currentItem)
                player.seek(to: .zero)
                player.play()
            }
            NSLog("RemoteAudioAsset Loop: About to start timer updates")
            startCurrentTimeUpdates()  // Add timer start
        }
    }

    @objc func playerItemDidReachEnd(notification: Notification) {
        owner.executeOnAudioQueue { [self] in
            if let playerItem = notification.object as? AVPlayerItem,
               let player = players.first(where: { $0.currentItem == playerItem }) {
                player.seek(to: .zero)
                player.play()
            }
        }
    }

    override func unload() {
        owner.executeOnAudioQueue { [self] in
            stopCurrentTimeUpdates()
            stop()
            NotificationCenter.default.removeObserver(self)
            // Remove KVO observers
            for observer in playerObservers {
                observer.invalidate()
            }
            playerObservers = []
            players = []
            playerItems = []
        }
    }

    override func setVolume(volume: NSNumber!) {
        owner.executeOnAudioQueue { [self] in
            for player in players {
                player.volume = volume.floatValue
            }
        }
    }

    override func setRate(rate: NSNumber!) {
        owner.executeOnAudioQueue { [self] in
            for player in players {
                player.rate = rate.floatValue
            }
        }
    }

    override func isPlaying() -> Bool {
        var result = false
        owner.executeOnAudioQueue { [self] in
            guard !players.isEmpty else {
                result = false
                return
            }
            let player = players[playIndex]
            result = player.timeControlStatus == .playing
        }
        return result
    }

    override func getCurrentTime() -> TimeInterval {
        var result: TimeInterval = 0
        owner.executeOnAudioQueue { [self] in
            guard !players.isEmpty else {
                result = 0
                return
            }
            let player = players[playIndex]
            result = player.currentTime().seconds
        }
        return result
    }

    override func getDuration() -> TimeInterval {
        var result: TimeInterval = 0
        owner.executeOnAudioQueue { [self] in
            guard !players.isEmpty else {
                result = 0
                return
            }
            let player = players[playIndex]
            if player.currentItem?.duration == CMTime.indefinite {
                result = 0
                return
            }
            result = player.currentItem?.duration.seconds ?? 0
        }
        return result
    }

    override func playWithFade(time: TimeInterval) {
        owner.executeOnAudioQueue { [self] in
            guard !players.isEmpty else { return }
            let player = players[playIndex]
            
            if player.timeControlStatus != .playing {
                player.seek(to: CMTimeMakeWithSeconds(time, preferredTimescale: 1))
                player.volume = initialVolume
                player.play()
                playIndex = (playIndex + 1) % players.count
                NSLog("RemoteAudioAsset PlayWithFade: About to start timer updates")
                startCurrentTimeUpdates()
            } else {
                if player.volume < initialVolume {
                    player.volume += self.FADESTEP
                }
            }
        }
    }

    override func stopWithFade() {
        owner.executeOnAudioQueue { [self] in
            guard !players.isEmpty else { return }
            let player = players[playIndex]
            
            if player.timeControlStatus == .playing {
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

    static func clearCache() {
        DispatchQueue.global(qos: .background).sync {
            let urls = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)
            if let cachePath = urls.first {
                do {
                    let fileURLs = try FileManager.default.contentsOfDirectory(at: cachePath, includingPropertiesForKeys: nil)
                    for fileURL in fileURLs where fileURL.pathExtension == "mp3" || fileURL.pathExtension == "wav" {
                        try FileManager.default.removeItem(at: fileURL)
                    }
                } catch {
                    print("Error clearing audio cache: \(error)")
                }
            }
        }
    }
}
