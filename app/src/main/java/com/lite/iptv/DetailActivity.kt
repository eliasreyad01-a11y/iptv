package com.lite.iptv

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Movie / series details with Watch Now (or Resume), favorite, and seasons + episodes for series. */
class DetailActivity : AppCompatActivity() {
    private lateinit var vod: Vod
    private var eps: List<Episode> = emptyList()
    private lateinit var tvTitle: TextView
    private lateinit var tvMeta: TextView
    private lateinit var tvPlot: TextView
    private lateinit var btnPlay: Button
    private lateinit var btnFav: Button
    private lateinit var epPane: View
    private lateinit var rvEps: RecyclerView

    private val seasonAd = Adapter<Int>(R.layout.item_cat) { v, s, _ ->
        v.findViewById<TextView>(R.id.tvName).text = getString(R.string.season, s)
        v.findViewById<TextView>(R.id.tvCount).text = eps.count { it.season == s }.toString()
    }
    private val epAd = Adapter<Episode>(R.layout.item_episode) { v, e, _ ->
        v.findViewById<TextView>(R.id.tvEp).text = "E${e.num}"
        v.findViewById<TextView>(R.id.tvName).text = e.title.ifBlank { vod.name }
        val p = Store.progress(e.url)
        v.findViewById<TextView>(R.id.tvProg).text = if (p > 0) "▶ " + fmtTime(p) else ""
    }

    @Suppress("DEPRECATION")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_detail)
        vod = intent.getSerializableExtra("vod") as? Vod ?: run { finish(); return }
        tvTitle = findViewById(R.id.tvTitle); tvMeta = findViewById(R.id.tvMeta); tvPlot = findViewById(R.id.tvPlot)
        btnPlay = findViewById(R.id.btnPlay); btnFav = findViewById(R.id.btnFav)
        epPane = findViewById(R.id.epPane); rvEps = findViewById(R.id.rvEps)
        findViewById<RecyclerView>(R.id.rvSeasons).setup(LinearLayoutManager(this), seasonAd)
        rvEps.setup(LinearLayoutManager(this), epAd)

        seasonAd.onFocus = { s, i -> seasonAd.select(i); epAd.submit(eps.filter { it.season == s }) }
        epAd.onClick = { e, _ -> playEpisode(e) }
        btnPlay.setOnClickListener { onPlay() }
        btnFav.setOnClickListener { Store.toggleFav(Store.vodFavKey(vod)); updateFav() }
        fill()
        btnPlay.requestFocus()

        lifecycleScope.launch {
            try {
                if (vod.isSeries) {
                    val (v, e) = Repo.seriesInfo(applicationContext, vod)
                    vod = v; eps = e
                    showSeries()
                } else vod = Repo.vodInfo(applicationContext, vod)
                fill()
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { if (vod.plot.isBlank()) tvPlot.text = e.message ?: "" }
        }
    }

    override fun onResume() {
        super.onResume()
        updatePlayLabel()
        epAd.notifyItemRangeChanged(0, epAd.itemCount)
    }

    private fun fill() {
        tvTitle.text = vod.name
        tvMeta.text = listOf(vod.year, vod.rating.takeIf { it.isNotBlank() && it != "0" }?.let { getString(R.string.rating_fmt, it) } ?: "", vod.extra)
            .filter { it.isNotBlank() }.joinToString("   •   ")
        tvPlot.text = vod.plot
        findViewById<ImageView>(R.id.ivPoster).let { if (vod.poster.isNotBlank()) it.load(vod.poster) { size(320, 480) } }
        updateFav()
        updatePlayLabel()
    }

    private fun updateFav() {
        btnFav.setText(if (Store.isFav(Store.vodFavKey(vod))) R.string.remove_fav else R.string.add_fav)
    }

    private fun resumeEpisode(): Episode? = eps.lastOrNull { Store.progress(it.url) > 0 }

    private fun updatePlayLabel() {
        val pos = if (vod.isSeries) resumeEpisode()?.let { Store.progress(it.url) } ?: 0L else Store.progress(vod.url)
        btnPlay.text = if (pos > 0) getString(R.string.resume, fmtTime(pos)) else getString(R.string.watch_now)
    }

    private fun showSeries() {
        if (eps.isEmpty()) return
        epPane.visibility = View.VISIBLE
        val seasons = eps.map { it.season }.distinct().sorted()
        seasonAd.submit(seasons)
        val start = seasons.indexOf((resumeEpisode() ?: eps.first()).season).coerceAtLeast(0)
        seasonAd.select(start)
        epAd.submit(eps.filter { it.season == seasons[start] })
    }

    private fun onPlay() {
        if (vod.isSeries) {
            val e = resumeEpisode() ?: eps.firstOrNull() ?: return
            playEpisode(e)
        } else {
            Store.pushRecent(vod)
            PlayQueue.set(false, listOf(QItem(vod.id, vod.name, vod.year, vod.url)), 0)
            startActivity(Intent(this, PlayerActivity::class.java))
        }
    }

    private fun playEpisode(e: Episode) {
        Store.pushRecent(vod)
        val q = eps.map { QItem(it.id, vod.name, "S%02dE%02d  %s".format(it.season, it.num, it.title), it.url) }
        PlayQueue.set(false, q, eps.indexOf(e).coerceAtLeast(0))
        startActivity(Intent(this, PlayerActivity::class.java))
    }
}
