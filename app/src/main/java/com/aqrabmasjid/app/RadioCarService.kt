package com.aqrabmasjid.app

import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Quran radio on the car screen (Android Auto).
 * Android Auto shows the stations as a list; the driver taps one to play.
 * Uses the same stations as the radio on the phone.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class RadioCarService : MediaLibraryService() {

    companion object {
        private const val ROOT = "root"
        /** Running car radio (so the phone radio can pause it). */
        @Volatile var instance: RadioCarService? = null
            private set

        /** Called when the phone radio starts: stop the car radio so both don't play. */
        fun pauseCar() { instance?.player?.pause() }
    }

    private var player: ExoPlayer? = null
    private var session: MediaLibrarySession? = null
    private var urlIndex = 0

    override fun onCreate() {
        super.onCreate()
        L.init(this)
        val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
            .setUserAgent(RadioService.UA).setConnectTimeoutMs(15_000).setReadTimeoutMs(20_000)
        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http)))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) = tryNextUrl()
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // Car radio started: stop the phone radio, so they don't play together
                if (isPlaying && RadioService.current != null) RadioService.stop(this@RadioCarService)
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) urlIndex = 0
            }
        })
        player = p
        session = MediaLibrarySession.Builder(this, p, Callback()).build()
        instance = this
    }

    /** A station stream failed: try its next address (each station has backups). */
    private fun tryNextUrl() {
        val p = player ?: return
        val st = RadioService.station(p.currentMediaItem?.mediaId) ?: return
        urlIndex++
        if (urlIndex >= st.urls.size) { urlIndex = 0; return }
        p.setMediaItem(item(st, urlIndex))
        p.prepare()
        p.play()
    }

    private fun item(st: RadioService.Station, urlIdx: Int = 0): MediaItem =
        MediaItem.Builder()
            .setMediaId(st.id)
            .setUri(st.urls[urlIdx.coerceIn(0, st.urls.size - 1)])
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(st.label)
                    .setArtist(L.t("بث مباشر", "Live"))
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
                    .setArtworkUri(Uri.parse("android.resource://$packageName/${R.mipmap.ic_launcher}"))
                    .build()
            )
            .build()

    private val root: MediaItem
        get() = MediaItem.Builder()
            .setMediaId(ROOT)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(L.t("إذاعة القرآن", "Quran radio"))
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS)
                    .build()
            )
            .build()

    private inner class Callback : MediaLibrarySession.Callback {
        override fun onGetLibraryRoot(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(root, params))

        override fun onGetChildren(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String,
            page: Int, pageSize: Int, params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val list = if (parentId == ROOT) RadioService.STATIONS.map { item(it) } else emptyList()
            return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(list), params))
        }

        override fun onGetItem(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val st = RadioService.station(mediaId)
            return Futures.immediateFuture(
                if (st != null) LibraryResult.ofItem(item(st), null)
                else LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
            )
        }

        // The car sends only the station id: add the stream address before playing
        override fun onAddMediaItems(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            urlIndex = 0
            val out = mediaItems.mapNotNull { m -> RadioService.station(m.mediaId)?.let { item(it) } }.toMutableList()
            return Futures.immediateFuture(out)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        val p = player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        instance = null
        session?.release()
        session = null
        player?.release()
        player = null
        super.onDestroy()
    }
}
