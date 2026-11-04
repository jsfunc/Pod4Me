package com.pod4me.domain

import java.net.URI
import java.util.UUID

data class RadioStation(val name: String, val url: String) {
    val id: String get() = "radio:" + UUID.nameUUIDFromBytes(url.toByteArray(Charsets.UTF_8))

    companion object {
        fun create(name: String, url: String): RadioStation {
            require(name.trim().isNotEmpty() && name.trim().length <= 120) { "Enter a station name (up to 120 characters)." }
            require(url.trim().length <= 4096) { "The stream URL is too long." }
            val normalized = FeedParser.httpUrl(url.trim())
            require(URI(normalized).fragment == null) { "Enter the direct stream URL without a # fragment." }
            return RadioStation(name.trim(), normalized)
        }
        fun isRadio(id: String?) = id?.startsWith("radio:") == true
    }
}

/** Bundled broadcaster streams, checked on 2026-11-04. Browsing needs no network/API. */
object RadioCatalogue {
    private val france = listOf(
        RadioStation("France Info", "https://icecast.radiofrance.fr/franceinfo-midfi.mp3"),
        RadioStation("France Culture", "https://icecast.radiofrance.fr/franceculture-midfi.mp3"),
        RadioStation("Europe 2", "https://europe2.lmn.fm/europe2.mp3"),
        RadioStation("France Inter", "https://icecast.radiofrance.fr/franceinter-midfi.mp3"),
        RadioStation("France Musique", "https://icecast.radiofrance.fr/francemusique-midfi.mp3"),
        RadioStation("FIP", "https://icecast.radiofrance.fr/fip-midfi.mp3"),
        RadioStation("Mouv’", "https://icecast.radiofrance.fr/mouv-midfi.mp3"),
        RadioStation("Europe 1", "https://europe1.lmn.fm/europe1.mp3"),
        RadioStation("Tonic Radio — Lyon", "https://tonicradiolyon.ice.infomaniak.ch/tonicradiolyonfm.mp3"),
        RadioStation("RTL", "https://icecast.rtl.fr/rtl-1-44-128"),
        RadioStation("RTL2", "https://icecast.rtl2.fr/rtl2-1-44-128"),
        RadioStation("Fun Radio", "https://icecast.funradio.fr/fun-1-44-128"),
        RadioStation("RMC", "https://audio.bfmtv.com/rmcradio_128.mp3"),
        RadioStation("BFM Business", "https://audio.bfmtv.com/bfmbusiness_128.mp3"),
        RadioStation("BFM Radio", "https://audio.bfmtv.com/bfmradio_128.mp3"),
        RadioStation("NRJ", "https://streaming.nrjaudio.fm/ou5k6tirv93h"),
        RadioStation("Nostalgie", "https://streaming.nrjaudio.fm/oug7girb92oc"),
        RadioStation("Chérie FM", "https://cdnradio.streamakaci.com/cheriefm1.mp3"),
        RadioStation("Rire et Chansons", "https://streaming.nrjaudio.fm/ou8o8xgk7oiu"),
        RadioStation("Skyrock", "https://icecast.skyrock.net/s/natio_mp3_128k"),
        RadioStation("RFM", "https://rfm.lmn.fm/rfm.mp3"),
        RadioStation("Radio Classique", "https://radioclassique.ice.infomaniak.ch/radioclassique-high.mp3"),
        RadioStation("Sud Radio", "https://ice.creacast.com/sudradio"),
        RadioStation("Radio Nova", "https://novazz.ice.infomaniak.ch/novazz-128.mp3"),
        RadioStation("OÜI FM", "https://ouifm.ice.infomaniak.ch/ouifm-high.mp3"),
        RadioStation("TSF Jazz", "https://tsfjazz.ice.infomaniak.ch/tsfjazz-high.mp3"),
        RadioStation("Jazz Radio", "https://jazzradio.ice.infomaniak.ch/jazzradio-high.mp3"),
        RadioStation("M Radio", "https://mfm.ice.infomaniak.ch/mfm-128.mp3"),
        RadioStation("Radio FG", "https://radiofg.impek.com/fg"),
        RadioStation("Latina", "https://start-latina.ice.infomaniak.ch/start-latina-high.mp3"),
        RadioStation("Ado", "https://start-adofm.ice.infomaniak.ch/start-adofm-high.mp3"),
        RadioStation("Beur FM", "https://beurfm.ice.infomaniak.ch/beurfm-high.mp3"),
        RadioStation("Radio Orient", "https://stream.rcs.revma.com/7hnrkawf4p8uv"),
        RadioStation("RFI Monde", "https://rfiafrique64k.ice.infomaniak.ch/rfimonde-64.mp3"),
        RadioStation("Chante France", "https://stream.chantefrance.com/Chante_France"),
        RadioStation("Crooner Radio", "https://direct.croonerradio.fr/croonerradio-midfi.mp3"),
        RadioStation("Générations", "https://generationfm.ice.infomaniak.ch/generationfm-high.mp3"),
        RadioStation("AirZen Radio", "https://stream.rcs.revma.com/vuumdncf368uv"),
        RadioStation("KTO Radio", "https://liveradiokto.akamaized.net/hls/live/20000054/ktoradio/02.m3u8"),
        RadioStation("RCF", "https://rcf.streamakaci.com/rcfdigital.mp3"),
        RadioStation("Africa Radio", "https://african1paris.ice.infomaniak.ch/african1paris-128.mp3"),
        RadioStation("Melody Radio", "https://stream.rcs.revma.com/5a05fstup42vv"),
        RadioStation("France Maghreb 2", "https://francemaghreb2.ice.infomaniak.ch/francemaghreb2-high.mp3"),
        RadioStation("Mon petit France Inter", "https://stream.radiofrance.fr/monpetitfranceinter/monpetitfranceinter_hifi.m3u8"),
        RadioStation("Nostalgie 60/70", "https://streaming.nrjaudio.fm/ougfkxrb92oc"),
    )
    val groups: List<Pair<String, List<RadioStation>>> = listOf(
        "France" to france,
        "United Kingdom" to listOf(
            RadioStation("BBC World Service", "https://stream.live.vc.bbcmedia.co.uk/bbc_world_service"),
            RadioStation("BBC Radio 4", "https://as-hls-ww-live.akamaized.net/pool_55057080/live/ww/bbc_radio_fourfm/bbc_radio_fourfm.isml/bbc_radio_fourfm-audio=128000.norewind.m3u8"),
            RadioStation("BBC Radio 2", "https://as-hls-ww-live.akamaized.net/pool_74208725/live/ww/bbc_radio_two/bbc_radio_two.isml/bbc_radio_two-audio=128000.norewind.m3u8"),
            RadioStation("LBC", "https://media-ice.musicradio.com/LBCUKMP3"),
            RadioStation("Classic FM", "https://media-ice.musicradio.com/ClassicFMMP3"),
        ),
        "United States" to listOf(
            RadioStation("NPR — Program Stream", "https://npr-ice.streamguys1.com/live.mp3"),
            RadioStation("WNYC — New York", "https://fm939.wnyc.org/wnycfm"),
            RadioStation("WBUR — Boston", "https://fm909.wbur.org/wbur_www"),
            RadioStation("KCRW — Los Angeles", "https://streams.kcrw.com/kcrw_mp3"),
            RadioStation("KEXP — Seattle", "https://kexp.streamguys1.com/kexp160.aac"),
        ),
        "Spain" to listOf(
            RadioStation("RNE Radio Nacional", "https://rtvelivestream.rtve.es/rtvesec/rne/rne_r1_main.m3u8"),
            RadioStation("Cadena SER", "https://playerservices.streamtheworld.com/api/livestream-redirect/CADENASER.mp3"),
        ),
        "Germany" to listOf(
            RadioStation("Deutschlandfunk", "https://st01.sslstream.dlf.de/dlf/01/128/mp3/stream.mp3"),
            RadioStation("Deutschlandfunk Kultur", "https://st02.sslstream.dlf.de/dlf/02/128/mp3/stream.mp3"),
        ),
    )
    val stations = groups.flatMap { it.second }
    // Keep the three original defaults and stream identities stable across catalogue updates.
    val defaults = france.take(3)
}
