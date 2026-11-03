package com.headswap.desk.desktop

/** Pixel bounds in the parent Activity. The author's full-size window remains underneath. */
internal data class DashboardViewport(val appWidth: Int, val appHeight: Int, val providerVisible: Boolean) {
    companion object {
        fun measure(width: Int, height: Int, musicVisible: Boolean, targetVisible: Boolean,
            dockHeight: Int, maximumDockHeight: Int): DashboardViewport {
            require(width > 0 && height > 0)
            val musicWidth = if (musicVisible) width * 3 / 10 else 0
            val dock = dockHeight.coerceIn(0, minOf(maximumDockHeight, height / 5))
            return DashboardViewport(width - musicWidth, height - dock, !targetVisible || musicWidth > 0 || dock > 0)
        }
    }
}
