import { WebPlugin } from '@capacitor/core';

import { AudioAsset } from './audio-asset';
import type {
  Assets,
  AssetPauseOptions,
  AssetPlayOptions,
  AssetRate,
  AssetResumeOptions,
  AssetSetTime,
  AssetStopOptions,
  AssetVolume,
  ConfigureOptions,
  PlayOnceOptions,
  PlayOnceResult,
  PreloadOptions,
} from './definitions';
import { NativeAudio } from './definitions';

export class NativeAudioWeb extends WebPlugin implements NativeAudio {
  private static readonly LOG_TAG: string = '[NativeAudioWeb]';
  private static readonly DEFAULT_FADE_DURATION_SEC: number = 1;
  private static readonly FILE_LOCATION: string = '';
  private static readonly AUDIO_ASSET_BY_ASSET_ID: Map<string, AudioAsset> = new Map<string, AudioAsset>();
  private static readonly playOnceAssets: Set<string> = new Set<string>();
  private debugMode = false;

  constructor() {
    super();
  }

  async resume(options: AssetResumeOptions): Promise<void> {
    const audio: HTMLAudioElement = this.getAudioAsset(options.assetId).audio;
    if (options.fadeIn) {
      const target = audio.volume > 0 ? audio.volume : 1;
      audio.volume = 0;
      await audio.play();
      this.fadeVolume(audio, target, options.fadeInDuration ?? NativeAudioWeb.DEFAULT_FADE_DURATION_SEC);
      return;
    }
    if (audio.paused) {
      return audio.play();
    }
  }

  async pause(options: AssetPauseOptions): Promise<void> {
    const audio: HTMLAudioElement = this.getAudioAsset(options.assetId).audio;
    if (options.fadeOut) {
      const target = audio.volume;
      this.fadeVolume(audio, 0, options.fadeOutDuration ?? NativeAudioWeb.DEFAULT_FADE_DURATION_SEC, () => {
        audio.pause();
        audio.volume = target;
      });
      return;
    }
    return audio.pause();
  }

  async setCurrentTime(options: AssetSetTime): Promise<void> {
    const audio: HTMLAudioElement = this.getAudioAsset(options.assetId).audio;
    audio.currentTime = options.time;
    return;
  }

  async getCurrentTime(options: Assets): Promise<{ currentTime: number }> {
    const audio: HTMLAudioElement = this.getAudioAsset(options.assetId).audio;
    return { currentTime: audio.currentTime };
  }

  async getDuration(options: Assets): Promise<{ duration: number }> {
    const audio: HTMLAudioElement = this.getAudioAsset(options.assetId).audio;
    if (Number.isNaN(audio.duration)) {
      throw 'no duration available';
    }
    if (!Number.isFinite(audio.duration)) {
      throw 'duration not available => media resource is streaming';
    }
    return { duration: audio.duration };
  }

  async setDebugMode(options: { enabled: boolean }): Promise<void> {
    this.debugMode = options.enabled;
    if (this.debugMode) {
      this.logInfo('Debug mode enabled');
    }
  }

  async configure(options: ConfigureOptions): Promise<void> {
    throw `configure is not supported for web: ${JSON.stringify(options)}`;
  }

  async isPreloaded(options: PreloadOptions): Promise<{ found: boolean }> {
    try {
      return { found: !!this.getAudioAsset(options.assetId) };
    } catch (e) {
      return { found: false };
    }
  }

  async preload(options: PreloadOptions): Promise<void> {
    if (NativeAudioWeb.AUDIO_ASSET_BY_ASSET_ID.has(options.assetId)) {
      throw 'AssetId already exists. Unload first if like to change!';
    }
    if (!options.assetPath?.length) {
      throw 'no assetPath provided';
    }
    if (!options.isUrl && !new RegExp('^/?' + NativeAudioWeb.FILE_LOCATION).test(options.assetPath)) {
      const slashPrefix: string = options.assetPath.startsWith('/') ? '' : '/';
      options.assetPath = `${NativeAudioWeb.FILE_LOCATION}${slashPrefix}${options.assetPath}`;
    }
    const audio: HTMLAudioElement = new Audio(options.assetPath);
    audio.autoplay = false;
    audio.loop = false;
    audio.preload = 'auto';
    if (options.volume) {
      audio.volume = options.volume;
    }
    NativeAudioWeb.AUDIO_ASSET_BY_ASSET_ID.set(options.assetId, new AudioAsset(audio));
  }

  async playOnce(options: PlayOnceOptions): Promise<PlayOnceResult> {
    // Generate a unique temporary asset ID
    const assetId = `playOnce_${Date.now()}_${Math.random().toString(36).substring(2, 9)}`;
    NativeAudioWeb.playOnceAssets.add(assetId);

    const autoPlay = options.autoPlay !== false; // Default to true
    const deleteAfterPlay = options.deleteAfterPlay ?? false;

    try {
      // Preload the asset
      await this.preload({
        assetId,
        assetPath: options.assetPath,
        volume: options.volume,
        isUrl: options.isUrl,
      });

      // Set up automatic cleanup on completion
      const audio = this.getAudioAsset(assetId).audio;
      const cleanupHandler = async () => {
        try {
          // Unload the asset
          await this.unload({ assetId });
          NativeAudioWeb.playOnceAssets.delete(assetId);

          // Delete file if requested (Web can't actually delete files from disk)
          // This is a no-op on web, but we keep the interface consistent
          if (deleteAfterPlay) {
            console.warn('[NativeAudio] deleteAfterPlay is not supported on web platform. File deletion is ignored.');
          }
        } catch (error) {
          console.error('[NativeAudio] Error during playOnce cleanup:', error);
        }
      };

      audio.addEventListener('ended', cleanupHandler, { once: true });

      // Handle errors during playback - cleanup if play fails
      audio.addEventListener(
        'error',
        () => {
          cleanupHandler().catch((error) => {
            console.error('[NativeAudio] Error during error cleanup:', error);
          });
        },
        { once: true },
      );

      // Auto-play if requested
      if (autoPlay) {
        await this.play({ assetId });
      }

      return { assetId };
    } catch (error) {
      // Cleanup on failure
      try {
        await this.unload({ assetId });
        NativeAudioWeb.playOnceAssets.delete(assetId);
      } catch {
        // Ignore cleanup errors
      }
      throw error;
    }
  }

  private onEnded(assetId: string): void {
    this.notifyListeners('complete', { assetId });
  }

  async play(options: AssetPlayOptions): Promise<void> {
    const { assetId, time = 0, delay = 0 } = options;
    const audio = this.getAudioAsset(assetId).audio;
    if (delay > 0) {
      await new Promise((resolve) => setTimeout(resolve, delay * 1000));
    }
    await this.stop(options);
    audio.loop = false;
    audio.currentTime = time;
    if (typeof options.volume === 'number') {
      audio.volume = options.volume;
    }
    audio.addEventListener('ended', () => this.onEnded(assetId), {
      once: true,
    });
    if (options.fadeIn) {
      const targetVolume = typeof options.volume === 'number' ? options.volume : 1;
      audio.volume = 0;
      await audio.play();
      this.fadeVolume(audio, targetVolume, options.fadeInDuration ?? NativeAudioWeb.DEFAULT_FADE_DURATION_SEC);
    } else {
      await audio.play();
    }

    if (options.fadeOut) {
      const fadeOutDuration = options.fadeOutDuration ?? NativeAudioWeb.DEFAULT_FADE_DURATION_SEC;
      const startAt =
        typeof options.fadeOutStartTime === 'number'
          ? options.fadeOutStartTime
          : Number.isFinite(audio.duration)
            ? Math.max(0, audio.duration - fadeOutDuration)
            : -1;
      if (startAt >= 0) {
        const msUntilFade = Math.max(0, (startAt - time) * 1000);
        setTimeout(() => {
          this.fadeVolume(audio, 0, fadeOutDuration);
        }, msUntilFade);
      }
    }
  }

  async loop(options: Assets): Promise<void> {
    const audio: HTMLAudioElement = this.getAudioAsset(options.assetId).audio;
    await this.stop(options);
    audio.loop = true;
    return audio.play();
  }

  async stop(options: AssetStopOptions): Promise<void> {
    const audio: HTMLAudioElement = this.getAudioAsset(options.assetId).audio;
    if (options.fadeOut && !audio.paused) {
      const target = audio.volume;
      this.fadeVolume(audio, 0, options.fadeOutDuration ?? NativeAudioWeb.DEFAULT_FADE_DURATION_SEC, () => {
        audio.pause();
        audio.loop = false;
        audio.currentTime = 0;
        audio.volume = target;
      });
      return;
    }
    audio.pause();
    audio.loop = false;
    audio.currentTime = 0;
  }

  async unload(options: Assets): Promise<void> {
    await this.stop(options);
    NativeAudioWeb.AUDIO_ASSET_BY_ASSET_ID.delete(options.assetId);
  }

  async setVolume(options: AssetVolume): Promise<void> {
    if (typeof options?.volume !== 'number') {
      throw 'no volume provided';
    }

    const audio: HTMLAudioElement = this.getAudioAsset(options.assetId).audio;
    if (options.duration && options.duration > 0) {
      this.fadeVolume(audio, options.volume, options.duration);
      return;
    }
    audio.volume = options.volume;
  }

  async setRate(options: AssetRate): Promise<void> {
    if (typeof options?.rate !== 'number') {
      throw 'no rate provided';
    }

    const audio: HTMLAudioElement = this.getAudioAsset(options.assetId).audio;
    audio.playbackRate = options.rate;
  }

  async isPlaying(options: Assets): Promise<{ isPlaying: boolean }> {
    const audio: HTMLAudioElement = this.getAudioAsset(options.assetId).audio;
    return { isPlaying: !audio.paused };
  }

  async clearCache(): Promise<void> {
    this.logWarning('clearCache is not supported for web. No cache to clear.');
    return;
  }

  private fadeVolume(audio: HTMLAudioElement, to: number, durationSec: number, onDone?: () => void): void {
    const from = audio.volume;
    if (durationSec <= 0) {
      audio.volume = Math.max(0, Math.min(1, to));
      onDone?.();
      return;
    }
    const intervalMs = 50;
    const steps = Math.max(1, Math.round((durationSec * 1000) / intervalMs));
    let step = 0;
    const timer = setInterval(() => {
      step++;
      const progress = step / steps;
      audio.volume = Math.max(0, Math.min(1, from + (to - from) * progress));
      if (step >= steps) {
        clearInterval(timer);
        onDone?.();
      }
    }, intervalMs);
  }

  private getAudioAsset(assetId: string): AudioAsset {
    this.checkAssetId(assetId);

    if (!NativeAudioWeb.AUDIO_ASSET_BY_ASSET_ID.has(assetId)) {
      throw `no asset for assetId "${assetId}" available. Call preload first!`;
    }

    return NativeAudioWeb.AUDIO_ASSET_BY_ASSET_ID.get(assetId) as AudioAsset;
  }

  private checkAssetId(assetId: string): void {
    if (typeof assetId !== 'string') {
      throw 'assetId must be a string';
    }

    if (!assetId?.length) {
      throw 'no assetId provided';
    }
  }

  private logWarning(message: string): void {
    if (!this.debugMode) return;
    console.warn(`${NativeAudioWeb.LOG_TAG} Warning: ${message}`);
  }

  private logInfo(message: string): void {
    if (!this.debugMode) return;
    console.info(`${NativeAudioWeb.LOG_TAG} Info: ${message}`);
  }

  async getPluginVersion(): Promise<{ version: string }> {
    return { version: 'web' };
  }

  async deinitPlugin(): Promise<void> {
    // Stop and unload all audio assets
    for (const [assetId] of NativeAudioWeb.AUDIO_ASSET_BY_ASSET_ID) {
      await this.unload({ assetId });
    }
    return;
  }
}

const NativeAudio = new NativeAudioWeb();

export { NativeAudio };
