package com.lite.iptv

import android.content.Intent
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load

/**
 * Live TV, and Sports (same list filtered to sports-looking categories).
 * OK on a channel plays it in the preview pane; OK again on the same channel opens full screen.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class LiveActivity : BrowseBase<Channel>() {

    override val screenKey get() = "live"
    override val titleRes get() = R.string.live
    override val itemLayout = R.layout.item_channel
    override fun makeLayoutManager(): RecyclerView.LayoutManager = LinearLayoutManager(this)
    override val hasPreview = true
    override suspend fun load() = Repo.live(applicationContext)
        .filter { !Store.isHidden(favKey(it)) }
        .map { c -> Store.groupOverride(favKey(c))?.let { c.copy(group = it) } ?: c }
    // Built-in/M3U lists: world categories first, then Arab countries (Jordan first), then the other countries A-Z.
    // Xtream: same order as the provider's categories.
    override fun groupRank(g: String): Int {
        if (Store.active()?.type != "m3u") return Repo.rank("live", g)
        val w = WORLD.indexOf(g)
        if (w >= 0) return w
        val a = ARAB.indexOf(g)
        if (a >= 0) return 10 + a
        val l = g.lowercase().padEnd(3, ' ')
        return 1000 + l[0].code * 10000 + l[1].code * 100 + l[2].code
    }
    private val WORLD = listOf("أخبار", "رياضة", "أطفال وكرتون", "مسلسلات وأفلام عربية", "أفلام عالمية", "وثائقي", "موسيقى", "عامة")
    private val ARAB = listOf("الأردن", "فلسطين", "السعودية", "الإمارات", "مصر", "العراق", "الكويت", "قطر",
        "البحرين", "عُمان", "لبنان", "سوريا", "اليمن", "ليبيا", "تونس", "الجزائر", "المغرب", "السودان", "موريتانيا")
    override fun groupOf(t: Channel) = t.group
    override fun nameOf(t: Channel) = t.name
    override fun favKey(t: Channel) = Store.favKey("L", t.id)

    override fun bind(v: View, t: Channel, i: Int) {
        v.findViewById<TextView>(R.id.tvNum).text = (i + 1).toString()
        v.findViewById<TextView>(R.id.tvName).text = t.name
        v.findViewById<View>(R.id.ivStar).visibility = if (Store.isFav(favKey(t))) View.VISIBLE else View.GONE
        val iv = v.findViewById<ImageView>(R.id.ivLogo)
        iv.dispose(); iv.setImageDrawable(null)
        iv.tag = t.logo
        if (t.logo.isNotBlank()) iv.postDelayed({ if (iv.tag == t.logo && iv.isAttachedToWindow) iv.load(t.logo) { size(96) } }, 250)
    }

    // ---------------- preview ----------------

    private var st: Streamer? = null
    private var prevCh: Channel? = null

    private fun errorDetail(e: PlaybackException): String {
        var c: Throwable? = e.cause
        while (c != null) {
            if (c is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) return "HTTP " + c.responseCode
            c = c.cause
        }
        return e.errorCodeName.removePrefix("ERROR_CODE_")
    }

    private val prevListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY) { st?.onReady(); findViewById<TextView>(R.id.tvPrevStatus).text = "" }
            else if (state == Player.STATE_BUFFERING) findViewById<TextView>(R.id.tvPrevStatus).setText(R.string.loading)
        }
        override fun onPlayerError(error: PlaybackException) {
            if (st?.onError(error) == true) findViewById<TextView>(R.id.tvPrevStatus).setText(R.string.loading)
            else findViewById<TextView>(R.id.tvPrevStatus).text = getString(R.string.play_error) + "\n" + errorDetail(error)
        }
    }

    // ---- in-place full screen: the SAME player view just grows to the whole screen, so nothing reloads ----
    private var fs = false
    private var fsList: List<Channel> = emptyList()
    private var fsIdx = 0
    private val infoHide = Runnable { findViewById<View>(R.id.tvFsInfo).visibility = View.GONE }
    private val fsBack = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { exitFs() }
    }

    override fun onCreate(b: android.os.Bundle?) {
        super.onCreate(b)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = findViewById<View>(R.id.root)
        val slot = findViewById<View>(R.id.flSlot)
        val place = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> placeOverlay() }
        slot.addOnLayoutChangeListener(place)
        root.addOnLayoutChangeListener(place)
        findViewById<View>(R.id.flPlayer).setOnClickListener { if (fs) exitFs() }
        onBackPressedDispatcher.addCallback(this, fsBack)
    }

    private fun placeOverlay() {
        if (fs) return
        val slot = findViewById<View>(R.id.flSlot); val fl = findViewById<View>(R.id.flPlayer); val root = findViewById<View>(R.id.root)
        if (slot.width == 0) return
        val a = IntArray(2); val r = IntArray(2)
        slot.getLocationInWindow(a); root.getLocationInWindow(r)
        val lp = fl.layoutParams as android.widget.FrameLayout.LayoutParams
        val x = a[0] - r[0]; val y = a[1] - r[1]
        if (lp.width != slot.width || lp.height != slot.height || lp.leftMargin != x || lp.topMargin != y) {
            lp.gravity = android.view.Gravity.TOP or android.view.Gravity.LEFT
            lp.width = slot.width; lp.height = slot.height; lp.leftMargin = x; lp.topMargin = y
            fl.layoutParams = lp
        }
        fl.visibility = View.VISIBLE
    }

    private fun enterFs(list: List<Channel>, index: Int) {
        fs = true; fsList = list; fsIdx = index
        val fl = findViewById<View>(R.id.flPlayer)
        val lp = fl.layoutParams as android.widget.FrameLayout.LayoutParams
        lp.width = -1; lp.height = -1; lp.leftMargin = 0; lp.topMargin = 0
        fl.layoutParams = lp
        findViewById<android.view.ViewGroup>(R.id.contentRoot).descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
        fsBack.isEnabled = true
        showInfo()
    }

    private fun exitFs() {
        if (!fs) return
        fs = false
        fsBack.isEnabled = false
        findViewById<View>(R.id.tvFsInfo).visibility = View.GONE
        findViewById<android.view.ViewGroup>(R.id.contentRoot).descendantFocusability = android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
        placeOverlay()
        focusItem(fsIdx)
    }

    private fun zap(d: Int) {
        if (fsList.isEmpty()) return
        fsIdx = (fsIdx + d + fsList.size) % fsList.size
        startPreview(fsList[fsIdx])
        showInfo()
    }

    private fun showInfo() {
        val t = findViewById<TextView>(R.id.tvFsInfo)
        t.text = "${fsIdx + 1}   ${fsList.getOrNull(fsIdx)?.name ?: ""}"
        t.visibility = View.VISIBLE
        t.removeCallbacks(infoHide); t.postDelayed(infoHide, 3500)
    }

    override fun dispatchKeyEvent(ev: android.view.KeyEvent): Boolean {
        if (!fs) return super.dispatchKeyEvent(ev)
        if (ev.action == android.view.KeyEvent.ACTION_DOWN) when (ev.keyCode) {
            android.view.KeyEvent.KEYCODE_DPAD_UP, android.view.KeyEvent.KEYCODE_CHANNEL_DOWN, android.view.KeyEvent.KEYCODE_PAGE_UP -> zap(-1)
            android.view.KeyEvent.KEYCODE_DPAD_DOWN, android.view.KeyEvent.KEYCODE_CHANNEL_UP, android.view.KeyEvent.KEYCODE_PAGE_DOWN -> zap(1)
            android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER, android.view.KeyEvent.KEYCODE_NUMPAD_ENTER,
            android.view.KeyEvent.KEYCODE_BACK, android.view.KeyEvent.KEYCODE_ESCAPE -> exitFs()
            android.view.KeyEvent.KEYCODE_MENU, android.view.KeyEvent.KEYCODE_INFO -> showInfo()
        }
        return true
    }

    override fun onStart() {
        super.onStart()
        val s = Streamer(this)
        s.p.addListener(prevListener)
        findViewById<PlayerView>(R.id.pvPreview).player = s.p
        st = s
        prevCh?.let { startPreview(it) }
    }

    override fun onStop() {
        findViewById<PlayerView>(R.id.pvPreview).player = null
        st?.release()
        st = null
        super.onStop()
    }

    private fun startPreview(c: Channel) {
        val s = st ?: return
        prevCh = c
        findViewById<TextView>(R.id.tvPrevTitle).text = c.name
        Store.setStr("last_live", c.id)
        s.play(c.url)
    }

    override fun open(list: List<Channel>, index: Int) {
        val c = list[index]
        if (prevCh?.url == c.url && st != null) enterFs(list, index)
        else startPreview(c)
    }

    override fun onLongItem(t: Channel): Boolean {
        val fav = Store.isFav(favKey(t))
        AlertDialog.Builder(this)
            .setTitle(t.name)
            .setItems(arrayOf(getString(if (fav) R.string.remove_fav else R.string.add_fav),
                getString(R.string.move_channel), getString(R.string.delete_channel))) { _, w ->
                when (w) {
                    0 -> toggleFav(t)
                    1 -> pickCategory(t)
                    else -> {
                        if (prevCh?.url == t.url) { st?.cancel(); st?.p?.stop(); prevCh = null; findViewById<TextView>(R.id.tvPrevTitle).text = "" }
                        Store.hide(favKey(t))
                        removeItem(t)
                        toast(R.string.channel_deleted)
                    }
                }
            }.show()
        return true
    }

    override fun onLongCat(key: String): Boolean {
        if (key.startsWith("*")) return false
        val items = groupItems(key)
        AlertDialog.Builder(this).setTitle(key)
            .setItems(arrayOf(getString(R.string.rename_category), getString(R.string.delete_category))) { _, w ->
                if (w == 0) {
                    val et = android.widget.EditText(this).apply { setText(key); setSelectAllOnFocus(true); setSingleLine() }
                    AlertDialog.Builder(this).setTitle(R.string.rename_category).setView(et)
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            val n = et.text.toString().trim()
                            if (n.isNotEmpty() && n != key) { Store.setGroups(items.map { favKey(it) }, n); reloadKeepCats() }
                        }.setNegativeButton(android.R.string.cancel, null).show()
                } else {
                    AlertDialog.Builder(this).setTitle(key)
                        .setMessage(getString(R.string.confirm_delete_category, items.size))
                        .setPositiveButton(R.string.delete_category) { _, _ ->
                            Store.hideMany(items.map { favKey(it) }); reloadKeepCats()
                            toast(R.string.channel_deleted)
                        }.setNegativeButton(android.R.string.cancel, null).show()
                }
            }.show()
        return true
    }

    /** "Move to category": choose an existing category or type a new one. */
    private fun pickCategory(t: Channel) {
        val names = groupNames().filter { it != t.group }
        val items = (names + getString(R.string.new_category)).toTypedArray()
        AlertDialog.Builder(this).setTitle(R.string.move_channel)
            .setItems(items) { _, i ->
                if (i < names.size) doMove(t, names[i])
                else {
                    val et = android.widget.EditText(this)
                    AlertDialog.Builder(this).setTitle(R.string.new_category).setView(et)
                        .setPositiveButton(android.R.string.ok) { _, _ -> et.text.toString().trim().takeIf { it.isNotEmpty() }?.let { doMove(t, it) } }
                        .setNegativeButton(android.R.string.cancel, null).show()
                }
            }.show()
    }

    private fun doMove(t: Channel, g: String) {
        val i = curIndex(t)
        Store.setGroup(favKey(t), g)
        toast(getString(R.string.moved_to, g))
        rebuild(i)
    }
}
