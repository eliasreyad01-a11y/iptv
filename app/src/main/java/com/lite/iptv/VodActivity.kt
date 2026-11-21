package com.lite.iptv

import android.content.Intent
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load

/** Movies and Series: poster grid. Items open the detail screen. */
class VodActivity : BrowseBase<Vod>() {
    private val isSeries by lazy { intent.getBooleanExtra("series", false) }

    override val screenKey get() = if (isSeries) "series" else "movies"
    override val titleRes get() = if (isSeries) R.string.series else R.string.movies
    override val itemLayout = R.layout.item_poster

    override fun makeLayoutManager(): RecyclerView.LayoutManager {
        val dm = resources.displayMetrics
        val span = (((dm.widthPixels / dm.density) - 330f) / 125f).toInt().coerceIn(3, 8)
        return GridLayoutManager(this, span)
    }

    override suspend fun load() = if (isSeries) Repo.series(applicationContext) else Repo.movies(applicationContext)
    override fun groupOf(t: Vod) = t.group
    override fun nameOf(t: Vod) = t.name
    override fun favKey(t: Vod) = Store.vodFavKey(t)
    override fun recent(): List<Vod> = Store.recent().filter { it.isSeries == isSeries }

    override fun bind(v: View, t: Vod, i: Int) {
        v.findViewById<TextView>(R.id.tvName).text = t.name
        v.findViewById<View>(R.id.ivStar).visibility = if (Store.isFav(favKey(t))) View.VISIBLE else View.GONE
        val iv = v.findViewById<ImageView>(R.id.ivPoster)
        if (t.poster.isBlank()) { iv.dispose(); iv.setImageDrawable(null) } else iv.load(t.poster) { size(300, 450) }
    }

    override fun open(list: List<Vod>, index: Int) {
        startActivity(Intent(this, DetailActivity::class.java).putExtra("vod", list[index]))
    }
}
