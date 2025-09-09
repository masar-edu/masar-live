import { useEffect, useRef } from 'react';
import Auth from '/imports/ui/services/auth';
import audioManager from '/imports/ui/services/audio-manager';
import { getSettingsSingletonInstance } from '/imports/ui/services/settings';
import useVoiceActivity from './useVoiceActivity';
import logger from '/imports/startup/client/logger';
import AudioService from '/imports/ui/components/audio/service';

const MUTE_SOUND_PATH = '/resources/sounds/conference-muted.mp3';
const UNMUTE_SOUND_PATH = '/resources/sounds/conference-unmuted.mp3';

const useMuteSoundAlert = () => {
  const currentUserId = Auth.userID;
  const { data: voiceActivityStream } = useVoiceActivity();

  const currentUserVoiceState = voiceActivityStream
    ?.slice()
    .reverse()
    .find((activity) => activity.userId === currentUserId);
  const isMuted = currentUserVoiceState?.muted;

  const prevMutedStateRef = useRef<boolean | undefined>(isMuted);

  useEffect(() => {
    const Settings = getSettingsSingletonInstance();
    const playAudio = Settings?.application?.muteUnmuteAudioAlerts;
    const isLiveKitBridge = audioManager.bridge?.bridgeName === 'livekit';

    if (!isLiveKitBridge || !playAudio) {
      prevMutedStateRef.current = isMuted;
      return;
    }

    const hasMuteStateChanged = prevMutedStateRef.current !== isMuted;

    logger.debug({
      logCode: 'useMuteSoundAlert_change_detection',
      extraInfo: { hasMuteStateChanged, current: isMuted, previous: prevMutedStateRef.current },
    }, 'Mute state change detection.');

    if (hasMuteStateChanged && typeof isMuted === 'boolean') {
      const soundToPlay = isMuted ? MUTE_SOUND_PATH : UNMUTE_SOUND_PATH;

      const basePath = window.meetingClientSettings.public.app.cdn
        + window.meetingClientSettings.public.app.basename;
      const fullSoundUrl = basePath + soundToPlay;

      logger.info({
        logCode: 'useMuteSoundAlert_play_triggered',
        extraInfo: { fullSoundUrl, reason: `State changed from ${prevMutedStateRef.current} to ${isMuted}` },
      }, 'Attempting to play mute/unmute sound via AudioService.');

      AudioService.playAlertSound(fullSoundUrl);
    }

    prevMutedStateRef.current = isMuted;
  }, [isMuted]);
};

export default useMuteSoundAlert;
