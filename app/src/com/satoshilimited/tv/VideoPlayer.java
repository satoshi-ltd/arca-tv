package com.satoshilimited.tv;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.SurfaceView;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.common.text.Cue;
import androidx.media3.common.text.CueGroup;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.audio.AudioCapabilities;
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class VideoPlayer {
    interface Listener {
        void onRatio(float ratio);
        void onFirstFrame();
        void onBuffering(boolean buffering);
        void onEnded();
        void onFailed(int httpCode);
        void onTracks();
        void onCues(List<Cue> cues);
    }

    static final class Track {
        Tracks.Group group;
        int index;
        int stream = -1;
        String label = "", detail = "", language = "";
        boolean selected, lazy;
        VideoLibrary.Sub sub;
    }

    private static final int MIN_BUFFER_MS = 20000, MAX_BUFFER_MS = 120000, START_MS = 2500, RESUME_MS = 6000;
    private static final int BUFFER_BYTES = 32 * 1024 * 1024;
    private static final int BUSY_RETRIES = 5, BUSY_DELAY_MS = 4000, SESSION_RETRIES = 3;
    private static final long UNREADABLE_MS = 10 * 60_000L, SUBTITLE_POLL_MS = 3000, SUBTITLE_MAX_MS = 30 * 60_000L;
    private static final Map<String, Long> UNREADABLE = new HashMap<>();
    private static final String HUB_ID = "arca:";
    private static final Pattern CUE_TIME = Pattern.compile("(\\d+:)?(\\d{2}):(\\d{2})\\.(\\d{3})");

    private final Context context;
    private final ExoPlayer player;
    private final DefaultTrackSelector selector;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor(), subtitleIo = Executors.newCachedThreadPool();
    private MediaItem item;
    private VideoLibrary.Item current;
    private String audioPreference = "", subtitlePreference = "";
    private boolean compatible, fellBack;
    private long offsetMs;
    private int audioStream = -1, sessionRetries, wantedStream = -1;
    private volatile int generation;
    private final Set<Integer> loaded = new HashSet<>(), fetching = new HashSet<>();

    VideoPlayer(Context c, SurfaceView surface, Listener listener) {
        context = c.getApplicationContext();
        this.listener = listener;
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", ArcaHub.authorization(c));
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setDefaultRequestProperties(headers)
                .setConnectTimeoutMs(10000)
                .setReadTimeoutMs(20000);
        DefaultLoadControl buffering = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(MIN_BUFFER_MS, MAX_BUFFER_MS, START_MS, RESUME_MS)
                .setTargetBufferBytes(BUFFER_BYTES)
                .setPrioritizeTimeOverSizeThresholds(false)
                .build();
        selector = new DefaultTrackSelector(c);
        selector.setParameters(selector.buildUponParameters().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true));
        DefaultRenderersFactory renderers = new DefaultRenderersFactory(c)
                .setEnableDecoderFallback(true)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF);
        player = new ExoPlayer.Builder(c, renderers)
                .setLoadControl(buffering)
                .setTrackSelector(selector)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(new DefaultDataSource.Factory(context, http)))
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
                .setHandleAudioBecomingNoisy(true)
                .build();
        player.setVideoSurfaceView(surface);
        player.addListener(new Player.Listener() {
            @Override public void onVideoSizeChanged(VideoSize size) {
                if (size.height > 0 && size.width > 0) listener.onRatio(size.width * size.pixelWidthHeightRatio / size.height);
            }

            @Override public void onRenderedFirstFrame() {
                sessionRetries = 0;
                listener.onFirstFrame();
            }

            @Override public void onPlaybackStateChanged(int state) {
                listener.onBuffering(state == Player.STATE_BUFFERING);
                if (state == Player.STATE_ENDED) listener.onEnded();
            }

            @Override public void onTracksChanged(Tracks tracks) {
                selectWanted(tracks);
                if (!compatible && current != null && tracks.containsType(C.TRACK_TYPE_AUDIO) && !tracks.isTypeSupported(C.TRACK_TYPE_AUDIO) && fallBack()) return;
                listener.onTracks();
            }

            @Override public void onCues(CueGroup group) {
                listener.onCues(group.cues);
            }

            @Override public void onPlayerError(PlaybackException error) {
                AppLog.w("arca video playback error " + error.getErrorCodeName() + ": " + error.getMessage());
                int code = 0;
                for (Throwable t = error.getCause(); t != null; t = t.getCause()) {
                    if (t instanceof HttpDataSource.InvalidResponseCodeException) {
                        code = ((HttpDataSource.InvalidResponseCodeException) t).responseCode;
                        break;
                    }
                }
                if (compatible && code == 404 && sessionRetries++ < SESSION_RETRIES) {
                    restart(position());
                    return;
                }
                if (!compatible && code == 0 && decodingFailed(error.errorCode)) {
                    if (fallBack()) return;
                    if (current != null && !servable(current)) code = 415;
                }
                listener.onFailed(code);
            }
        });
    }

    void play(VideoLibrary.Item v, long startMs, String audioLanguage, String subtitleLanguage) {
        generation++;
        current = v;
        audioPreference = audioLanguage;
        subtitlePreference = subtitleLanguage;
        compatible = false;
        fellBack = false;
        offsetMs = 0;
        sessionRetries = 0;
        wantedStream = -1;
        loaded.clear();
        fetching.clear();
        VideoLibrary.Audio audio = chosenAudio(v, audioLanguage);
        audioStream = audio == null ? -1 : audio.stream;
        if (audio != null && !playsDirectly(audio.codec) && servable(v)) {
            startCompatible(Math.max(0, startMs), 0);
            return;
        }
        String volume = ArcaHub.volume(context, "video");
        try {
            String base = ArcaHub.url(context);
            Uri media = Uri.parse(base + "/v1/video/media?volume=" + encode(volume) + "&path=" + encode(v.path) + "&hash=" + encode(v.hash));
            List<MediaItem.SubtitleConfiguration> subs = new ArrayList<>();
            int number = 0;
            for (VideoLibrary.Sub s : v.subtitles) {
                number++;
                if (s.path.isEmpty()) continue;
                Uri uri = Uri.parse(base + "/v1/video/subtitles?volume=" + encode(volume) + "&path=" + encode(s.path));
                subs.add(subtitle(uri, s, "x" + number));
            }
            item = new MediaItem.Builder().setUri(media).setSubtitleConfigurations(subs).build();
        } catch (Exception e) {
            AppLog.w("arca video uri: " + e.getMessage());
            return;
        }
        applyPreferences();
        player.setMediaItem(item, Math.max(0, startMs));
        player.prepare();
        player.play();
        startPreferred(v, 0);
    }

    private void applyPreferences() {
        DefaultTrackSelector.Parameters.Builder p = selector.buildUponParameters().clearOverridesOfType(C.TRACK_TYPE_TEXT).clearOverridesOfType(C.TRACK_TYPE_AUDIO);
        p.setPreferredAudioLanguage(compatible || audioPreference.isEmpty() ? null : audioPreference);
        boolean subsOn = !subtitlePreference.isEmpty();
        p.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !subsOn);
        p.setPreferredTextLanguage(subsOn ? subtitlePreference : null);
        selector.setParameters(p);
    }

    private static boolean servable(VideoLibrary.Item v) {
        switch (v.videoCodec.toLowerCase(Locale.ROOT)) {
            case "":
            case "h264":
            case "avc":
            case "avc1":
            case "hevc":
            case "h265":
            case "av1":
            case "vp9":
                return true;
            default:
                return false;
        }
    }

    private boolean fallBack() {
        if (fellBack || current == null || current.audio.isEmpty() || !servable(current)) return false;
        fellBack = true;
        long at = position();
        main.post(() -> restart(at));
        return true;
    }

    private void restart(long startMs) {
        if (current == null) return;
        generation++;
        startCompatible(Math.max(0, startMs), 0);
    }

    private void startCompatible(long startMs, int attempt) {
        int session = generation;
        VideoLibrary.Item v = current;
        String volume = ArcaHub.volume(context, "video");
        String copy = copyCodecs();
        int audio = audioStream;
        io.execute(() -> {
            try {
                String query = "/v1/video/compatible?volume=" + encode(volume) + "&path=" + encode(v.path) + "&hash=" + encode(v.hash)
                        + "&start=" + String.format(Locale.ROOT, "%.3f", startMs / 1000.0) + (audio >= 0 ? "&audio=" + audio : "") + (copy.isEmpty() ? "" : "&copy=" + copy);
                JSONObject answer = new JSONObject(ArcaHub.get(context, query, 100_000));
                long offset = Math.round(answer.optDouble("offset", 0) * 1000);
                Uri stream = Uri.parse(ArcaHub.url(context) + answer.getString("url"));
                List<MediaItem.SubtitleConfiguration> subs = shiftedSubtitles(v, volume, offset, session);
                main.post(() -> {
                    if (session != generation || v != current) return;
                    compatible = true;
                    offsetMs = offset;
                    if (answer.has("audio") && !answer.isNull("audio")) audioStream = answer.optInt("audio", audioStream);
                    item = new MediaItem.Builder().setUri(stream).setMimeType(MimeTypes.APPLICATION_M3U8).setSubtitleConfigurations(subs).build();
                    applyPreferences();
                    player.setMediaItem(item, 0);
                    player.prepare();
                    player.play();
                    startPreferred(v, offset);
                });
            } catch (Exception e) {
                int code = httpCode(e);
                AppLog.w("arca video compatible: " + e.getMessage());
                main.post(() -> {
                    if (session != generation || v != current) return;
                    if (code == 503 && attempt < BUSY_RETRIES) {
                        listener.onBuffering(true);
                        main.postDelayed(() -> {
                            if (session == generation && v == current) startCompatible(startMs, attempt + 1);
                        }, BUSY_DELAY_MS);
                    } else {
                        listener.onFailed(code);
                    }
                });
            }
        });
    }

    private List<MediaItem.SubtitleConfiguration> shiftedSubtitles(VideoLibrary.Item v, String volume, long offset, int session) {
        List<MediaItem.SubtitleConfiguration> out = new ArrayList<>();
        File dir = new File(context.getCacheDir(), "arca-subtitles");
        if (!dir.isDirectory() && !dir.mkdirs()) return out;
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) if (!f.getName().startsWith(session + "-")) f.delete();
        int index = 0;
        for (VideoLibrary.Sub s : v.subtitles) {
            index++;
            if (s.image) continue;
            try {
                if (s.path.isEmpty()) continue;
                String query = "/v1/video/subtitles?volume=" + encode(volume) + "&path=" + encode(s.path) + "&format=json";
                String text = new JSONObject(ArcaHub.get(context, query, 6_000_000)).optString("text", "");
                if (text.isEmpty()) continue;
                File file = new File(dir, session + "-" + index + ".vtt");
                try (FileOutputStream w = new FileOutputStream(file)) {
                    w.write(shift(text, offset).getBytes(StandardCharsets.UTF_8));
                }
                out.add(subtitle(Uri.fromFile(file), s, "x" + index));
            } catch (Exception e) {
                AppLog.w("arca video subtitles: " + e.getMessage());
            }
        }
        return out;
    }

    private boolean embeddedText(VideoLibrary.Sub s) {
        return s.embedded && s.path.isEmpty() && s.stream >= 0 && !s.image;
    }

    private void startPreferred(VideoLibrary.Item v, long offset) {
        Set<Integer> keep = new HashSet<>(loaded);
        keep.addAll(fetching);
        if (wantedStream >= 0) keep.add(wantedStream);
        loaded.clear();
        fetching.clear();
        VideoLibrary.Sub match = null;
        if (!subtitlePreference.isEmpty()) {
            for (VideoLibrary.Sub s : v.subtitles) {
                if (!embeddedText(s) || !subtitlePreference.equalsIgnoreCase(s.language)) continue;
                if (match == null || match.forced && !s.forced) match = s;
            }
        }
        for (VideoLibrary.Sub s : v.subtitles) {
            if (embeddedText(s) && (s == match || keep.contains(s.stream))) fetchEmbedded(v, s, offset);
        }
    }

    private void fetchEmbedded(VideoLibrary.Item v, VideoLibrary.Sub s, long offset) {
        String key = v.hash + ":" + s.stream;
        if (recentlyUnreadable(key) || loaded.contains(s.stream) || !fetching.add(s.stream)) return;
        int session = generation;
        String volume = ArcaHub.volume(context, "video");
        subtitleIo.execute(() -> {
            long deadline = SystemClock.uptimeMillis() + SUBTITLE_MAX_MS;
            while (session == generation && SystemClock.uptimeMillis() < deadline) {
                try {
                    ArcaHub.Response r = ArcaHub.reply(context, "/v1/video/subtitles?volume=" + encode(volume) + "&path=" + encode(v.path) + "&hash=" + encode(v.hash)
                            + "&stream=" + s.stream + "&poll=1&format=json", 6_000_000);
                    if (r.code == 422) {
                        markUnreadable(key);
                        main.post(() -> embeddedFailed(session, s));
                        return;
                    }
                    if (r.code == 200) {
                        String text = new JSONObject(r.body).optString("text", "");
                        File dir = new File(context.getCacheDir(), "arca-subtitles");
                        if (text.isEmpty() || !dir.isDirectory() && !dir.mkdirs()) {
                            main.post(() -> embeddedFailed(session, s));
                            return;
                        }
                        File file = new File(dir, session + "-e" + s.stream + ".vtt");
                        try (FileOutputStream w = new FileOutputStream(file)) {
                            w.write(shift(text, offset).getBytes(StandardCharsets.UTF_8));
                        }
                        MediaItem.SubtitleConfiguration config = subtitle(Uri.fromFile(file), s, "e" + s.stream);
                        main.post(() -> addEmbedded(session, v, s, config));
                        return;
                    }
                    if (r.code != 202) {
                        main.post(() -> embeddedFailed(session, s));
                        return;
                    }
                } catch (Exception e) {
                    AppLog.w("arca video embedded subtitles: " + e.getMessage());
                }
                try {
                    Thread.sleep(SUBTITLE_POLL_MS);
                } catch (InterruptedException e) {
                    return;
                }
            }
            main.post(() -> embeddedFailed(session, s));
        });
    }

    private void embeddedFailed(int session, VideoLibrary.Sub s) {
        if (session != generation) return;
        fetching.remove(s.stream);
        if (wantedStream == s.stream) wantedStream = -1;
        listener.onTracks();
    }

    private void addEmbedded(int session, VideoLibrary.Item v, VideoLibrary.Sub s, MediaItem.SubtitleConfiguration config) {
        if (session != generation || v != current || item == null) return;
        fetching.remove(s.stream);
        loaded.add(s.stream);
        List<MediaItem.SubtitleConfiguration> all = new ArrayList<>(item.localConfiguration == null ? new ArrayList<>() : item.localConfiguration.subtitleConfigurations);
        all.add(config);
        long at = player.getCurrentPosition();
        boolean play = player.getPlayWhenReady();
        item = item.buildUpon().setSubtitleConfigurations(all).build();
        player.setMediaItem(item, at);
        player.prepare();
        player.setPlayWhenReady(play);
    }

    private void selectWanted(Tracks tracks) {
        if (wantedStream < 0) return;
        String id = HUB_ID + "e" + wantedStream;
        for (Tracks.Group g : tracks.getGroups()) {
            if (g.getType() != C.TRACK_TYPE_TEXT) continue;
            for (int i = 0; i < g.length; i++) {
                if (!id.equals(g.getTrackFormat(i).id)) continue;
                wantedStream = -1;
                selector.setParameters(selector.buildUponParameters().clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setOverrideForType(new TrackSelectionOverride(g.getMediaTrackGroup(), i)));
                return;
            }
        }
    }

    private static boolean recentlyUnreadable(String key) {
        synchronized (UNREADABLE) {
            Long at = UNREADABLE.get(key);
            return at != null && SystemClock.uptimeMillis() - at < UNREADABLE_MS;
        }
    }

    private static void markUnreadable(String key) {
        synchronized (UNREADABLE) {
            UNREADABLE.put(key, SystemClock.uptimeMillis());
        }
    }

    static String shift(String vtt, long offsetMs) {
        if (offsetMs <= 0) return vtt;
        StringBuilder out = new StringBuilder();
        for (String block : vtt.replace("\r\n", "\n").split("\n\n")) {
            int arrow = block.indexOf("-->");
            if (arrow < 0) {
                out.append(block).append("\n\n");
                continue;
            }
            int lineStart = block.lastIndexOf('\n', arrow) + 1;
            int lineEnd = block.indexOf('\n', arrow);
            String timing = block.substring(lineStart, lineEnd < 0 ? block.length() : lineEnd);
            Matcher m = CUE_TIME.matcher(timing);
            long[] times = new long[2];
            int found = 0;
            while (found < 2 && m.find()) times[found++] = millis(m);
            if (found < 2 || times[1] - offsetMs <= 0) continue;
            String settings = timing.substring(Math.min(timing.length(), m.end()));
            out.append(block, 0, lineStart).append(clock(Math.max(0, times[0] - offsetMs))).append(" --> ").append(clock(times[1] - offsetMs)).append(settings)
                    .append(lineEnd < 0 ? "" : block.substring(lineEnd)).append("\n\n");
        }
        return out.toString();
    }

    private static long millis(Matcher m) {
        long hours = m.group(1) == null ? 0 : Long.parseLong(m.group(1).substring(0, m.group(1).length() - 1));
        return ((hours * 60 + Long.parseLong(m.group(2))) * 60 + Long.parseLong(m.group(3))) * 1000 + Long.parseLong(m.group(4));
    }

    private static String clock(long ms) {
        return String.format(Locale.ROOT, "%02d:%02d:%02d.%03d", ms / 3600000, ms / 60000 % 60, ms / 1000 % 60, ms % 1000);
    }

    private static MediaItem.SubtitleConfiguration subtitle(Uri uri, VideoLibrary.Sub s, String id) {
        return new MediaItem.SubtitleConfiguration.Builder(uri).setMimeType(MimeTypes.TEXT_VTT).setId(HUB_ID + id)
                .setSelectionFlags(s.forced ? C.SELECTION_FLAG_FORCED : 0)
                .setLanguage(s.language.isEmpty() ? null : s.language).setLabel(s.label.isEmpty() ? null : s.label).build();
    }

    private static VideoLibrary.Audio chosenAudio(VideoLibrary.Item v, String language) {
        if (v.audio.isEmpty()) return null;
        if (!language.isEmpty()) for (VideoLibrary.Audio a : v.audio) if (language.equalsIgnoreCase(a.language)) return a;
        for (VideoLibrary.Audio a : v.audio) if (a.preferred) return a;
        return v.audio.get(0);
    }

    private boolean playsDirectly(String codec) {
        String mime = audioMime(codec);
        return mime == null || handles(mime);
    }

    private String copyCodecs() {
        List<String> out = new ArrayList<>();
        if (handles(MimeTypes.AUDIO_AC3)) out.add("ac3");
        if (handles(MimeTypes.AUDIO_E_AC3)) out.add("eac3");
        return String.join(",", out);
    }

    @SuppressWarnings("deprecation")
    private boolean handles(String mime) {
        int encoding = MimeTypes.getEncoding(mime, null);
        if (encoding != C.ENCODING_INVALID && AudioCapabilities.getCapabilities(context).supportsEncoding(encoding)) return true;
        try {
            return !MediaCodecUtil.getDecoderInfos(mime, false, false).isEmpty();
        } catch (MediaCodecUtil.DecoderQueryException e) {
            return false;
        }
    }

    private static String audioMime(String codec) {
        switch (codec == null ? "" : codec.toLowerCase(Locale.ROOT)) {
            case "ac3": return MimeTypes.AUDIO_AC3;
            case "eac3": return MimeTypes.AUDIO_E_AC3;
            case "dts": return MimeTypes.AUDIO_DTS;
            case "truehd": return MimeTypes.AUDIO_TRUEHD;
            default: return null;
        }
    }

    private static boolean decodingFailed(int code) {
        return code == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED || code == PlaybackException.ERROR_CODE_DECODING_FAILED
                || code == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED || code == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES
                || code == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED || code == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED;
    }

    private static int httpCode(Exception e) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        if (!message.startsWith("HTTP ")) return 0;
        try {
            return Integer.parseInt(message.substring(5).trim());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static String encode(String value) throws IOException {
        return URLEncoder.encode(value, "UTF-8");
    }

    boolean playing() {
        return player.getPlayWhenReady() && player.getPlaybackState() != Player.STATE_ENDED;
    }

    boolean waiting() {
        return player.getPlaybackState() == Player.STATE_BUFFERING;
    }

    void toggle() {
        if (player.getPlaybackState() == Player.STATE_ENDED) return;
        if (player.getPlayWhenReady()) player.pause();
        else player.play();
    }

    void pause() {
        player.pause();
    }

    void resume() {
        player.play();
    }

    long position() {
        return (compatible ? offsetMs : 0) + Math.max(0, player.getCurrentPosition());
    }

    long duration() {
        if (compatible && current != null && current.duration > 0) return Math.round(current.duration * 1000);
        long d = player.getDuration();
        return d == C.TIME_UNSET ? 0 : (compatible ? offsetMs : 0) + d;
    }

    void seekTo(long ms) {
        long d = duration();
        long target = Math.max(0, d > 0 ? Math.min(ms, d - 500) : ms);
        if (!compatible) {
            player.seekTo(target);
            return;
        }
        long produced = player.getDuration();
        long relative = target - offsetMs;
        if (relative >= 0 && produced != C.TIME_UNSET && relative <= produced - 1000) player.seekTo(relative);
        else restart(target);
    }

    private void hubLabels(List<Track> tracks) {
        if (current == null || current.audio.isEmpty()) return;
        List<VideoLibrary.Audio> hub = current.audio;
        boolean[] used = new boolean[hub.size()];
        for (int i = 0; i < tracks.size(); i++) {
            Track t = tracks.get(i);
            int at = i < hub.size() && sameLanguage(t.language, hub.get(i).language) ? i : -1;
            for (int j = 0; at < 0 && j < hub.size(); j++) if (!used[j] && !t.language.isEmpty() && sameLanguage(t.language, hub.get(j).language)) at = j;
            if (at < 0) continue;
            used[at] = true;
            if (!hub.get(at).label.isEmpty()) t.label = hub.get(at).label;
        }
    }

    private static String iso3(String code) {
        try {
            return new Locale(code).getISO3Language().toLowerCase(Locale.ROOT);
        } catch (RuntimeException e) {
            return code.toLowerCase(Locale.ROOT);
        }
    }

    private static boolean sameLanguage(String a, String b) {
        return a.isEmpty() || b.isEmpty() || iso3(a).equals(iso3(b));
    }

    List<Track> audioTracks() {
        if (compatible) return hubAudioTracks(true);
        List<Track> out = tracks(C.TRACK_TYPE_AUDIO);
        hubLabels(out);
        for (Track t : hubAudioTracks(false)) if (t.stream >= 0) out.add(t);
        return out;
    }

    private List<Track> hubAudioTracks(boolean all) {
        List<Track> out = new ArrayList<>();
        if (current == null) return out;
        int number = 0;
        for (VideoLibrary.Audio a : current.audio) {
            number++;
            if (!all && playsDirectly(a.codec)) continue;
            Track t = new Track();
            t.stream = a.stream;
            t.language = a.language;
            t.selected = compatible && a.stream == audioStream;
            String shown = !a.label.isEmpty() ? a.label : a.language.isEmpty() ? "Track " + number : Locale.forLanguageTag(a.language).getDisplayLanguage(Locale.ENGLISH);
            t.label = shown;
            String ch = a.channels >= 8 ? "7.1" : a.channels >= 6 ? "5.1" : a.channels == 2 ? "2.0" : a.channels == 1 ? "1.0" : "";
            String codec = a.codec.toUpperCase(Locale.ROOT).replace("EAC3", "E-AC3");
            t.detail = ch.isEmpty() ? codec : ch + " · " + codec;
            out.add(t);
        }
        return out;
    }

    List<Track> textTracks() {
        List<Track> out = tracks(C.TRACK_TYPE_TEXT);
        if (current == null) return out;
        for (VideoLibrary.Sub s : current.subtitles) {
            if (!embeddedText(s) || loaded.contains(s.stream) || recentlyUnreadable(current.hash + ":" + s.stream)) continue;
            Track t = new Track();
            t.lazy = true;
            t.sub = s;
            t.stream = s.stream;
            t.language = s.language;
            t.selected = wantedStream == s.stream;
            Format f = new Format.Builder().setLanguage(s.language.isEmpty() ? null : s.language).setLabel(s.label.isEmpty() ? null : s.label)
                    .setSelectionFlags(s.forced ? C.SELECTION_FLAG_FORCED : 0).build();
            t.label = name(f, C.TRACK_TYPE_TEXT, out.size() + 1);
            t.detail = s.forced ? "Forced" : "";
            out.add(t);
        }
        return out;
    }

    private boolean hubServesEmbedded() {
        if (current == null) return false;
        for (VideoLibrary.Sub s : current.subtitles) if (embeddedText(s)) return true;
        return false;
    }

    boolean subtitlesOn() {
        return !selector.getParameters().disabledTrackTypes.contains(C.TRACK_TYPE_TEXT);
    }

    private List<Track> tracks(int type) {
        List<Track> out = new ArrayList<>();
        for (Tracks.Group g : player.getCurrentTracks().getGroups()) {
            if (g.getType() != type) continue;
            for (int i = 0; i < g.length; i++) {
                if (!g.isTrackSupported(i)) continue;
                Format f = g.getTrackFormat(i);
                if (type == C.TRACK_TYPE_TEXT && hubServesEmbedded() && (f.id == null || !f.id.startsWith(HUB_ID)) && !image(f)) continue;
                Track t = new Track();
                t.group = g;
                t.index = i;
                t.language = language(f);
                t.selected = g.isTrackSelected(i) && (type == C.TRACK_TYPE_AUDIO || subtitlesOn());
                t.label = name(f, type, out.size() + 1);
                t.detail = type == C.TRACK_TYPE_AUDIO ? sound(f) : kind(f);
                out.add(t);
            }
        }
        return out;
    }

    static String language(Format f) {
        return f.language == null || "und".equals(f.language) ? "" : f.language;
    }

    private static String name(Format f, int type, int number) {
        String lang = language(f);
        String shown = lang.isEmpty() ? "" : Locale.forLanguageTag(lang).getDisplayLanguage(Locale.ENGLISH);
        if (shown.isEmpty()) shown = f.label != null && !f.label.isEmpty() ? f.label : (type == C.TRACK_TYPE_AUDIO ? "Track " : "Subtitles ") + number;
        else if (f.label != null && !f.label.isEmpty() && !f.label.equalsIgnoreCase(shown) && !f.label.equalsIgnoreCase(lang)) shown = f.label.toLowerCase(Locale.ROOT).contains(shown.toLowerCase(Locale.ROOT)) ? f.label : shown + " · " + f.label;
        return shown;
    }

    private static String sound(Format f) {
        String ch = f.channelCount >= 8 ? "7.1" : f.channelCount >= 6 ? "5.1" : f.channelCount == 2 ? "2.0" : f.channelCount == 1 ? "1.0" : f.channelCount > 0 ? f.channelCount + " ch" : "";
        String codec = codec(f.sampleMimeType);
        return ch.isEmpty() ? codec : codec.isEmpty() ? ch : ch + " · " + codec;
    }

    private static String codec(String mime) {
        if (mime == null) return "";
        switch (mime) {
            case MimeTypes.AUDIO_AC3: return "AC3";
            case MimeTypes.AUDIO_E_AC3:
            case MimeTypes.AUDIO_E_AC3_JOC: return "E-AC3";
            case MimeTypes.AUDIO_TRUEHD: return "TrueHD";
            case MimeTypes.AUDIO_DTS: return "DTS";
            case MimeTypes.AUDIO_DTS_HD: return "DTS-HD";
            case MimeTypes.AUDIO_DTS_EXPRESS: return "DTS Express";
            case MimeTypes.AUDIO_AAC: return "AAC";
            case MimeTypes.AUDIO_FLAC: return "FLAC";
            case MimeTypes.AUDIO_OPUS: return "Opus";
            case MimeTypes.AUDIO_MPEG: return "MP3";
            case MimeTypes.AUDIO_VORBIS: return "Vorbis";
            default: return "";
        }
    }

    private static boolean image(Format f) {
        return MimeTypes.APPLICATION_PGS.equals(f.sampleMimeType) || MimeTypes.APPLICATION_DVBSUBS.equals(f.sampleMimeType) || MimeTypes.APPLICATION_VOBSUB.equals(f.sampleMimeType);
    }

    private static String kind(Format f) {
        if (image(f)) return "Image";
        return (f.selectionFlags & C.SELECTION_FLAG_FORCED) != 0 ? "Forced" : "";
    }

    void selectAudio(Track t) {
        if (t.stream >= 0) {
            if (compatible && t.stream == audioStream) return;
            audioStream = t.stream;
            restart(position());
            return;
        }
        selector.setParameters(selector.buildUponParameters().setOverrideForType(new TrackSelectionOverride(t.group.getMediaTrackGroup(), t.index)));
    }

    void selectText(Track t) {
        DefaultTrackSelector.Parameters.Builder p = selector.buildUponParameters().clearOverridesOfType(C.TRACK_TYPE_TEXT);
        wantedStream = -1;
        if (t != null && t.lazy) {
            wantedStream = t.stream;
            p.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false);
            selector.setParameters(p);
            fetchEmbedded(current, t.sub, compatible ? offsetMs : 0);
            listener.onTracks();
            return;
        }
        if (t == null) p.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true);
        else p.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setOverrideForType(new TrackSelectionOverride(t.group.getMediaTrackGroup(), t.index));
        selector.setParameters(p);
    }

    void stop() {
        generation++;
        item = null;
        current = null;
        compatible = false;
        player.stop();
        player.clearMediaItems();
    }

    void release() {
        generation++;
        current = null;
        item = null;
        main.removeCallbacksAndMessages(null);
        io.shutdownNow();
        subtitleIo.shutdownNow();
        player.release();
    }
}
