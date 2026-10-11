package coredevices.pebble.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val ElapsedLabelSteps = listOf(1, 2, 5, 10, 15, 20, 30, 60, 120)
private const val MAX_ELAPSED_LABELS = 6

@Composable
internal fun WorkoutStepsChart(
    stepsPerMinute: List<Int>,
    scrub: ScrubState,
    tm: TextMeasurer,
    modifier: Modifier = Modifier,
) {
    val n = stepsPerMinute.size
    val maxV = (stepsPerMinute.maxOrNull() ?: 0).coerceAtLeast(1)
    val si = scrub.scrubIndex

    Canvas(modifier.fillMaxWidth().scrubGesture(n, scrub)) {
        val cH = size.height - 14.dp.toPx()
        drawRect(ChartOverlayColor, Offset.Zero, Size(size.width, cH))
        if (n == 0) return@Canvas

        val sp = if (n > 60) 0f else 1.dp.toPx()
        val bW = (size.width - sp * (n - 1)) / n
        val heights = stepsPerMinute.map { it.toFloat() / maxV * cH }
        heights.forEachIndexed { i, h ->
            if (h <= 0f) return@forEachIndexed
            val a = when { si == null -> BarAlpha; si == i -> BarAlphaSelected; else -> BarAlphaDimmed }
            val prevH = heights.getOrElse(i - 1) { h }
            val nextH = heights.getOrElse(i + 1) { h }
            drawPath(smoothBarPath(i * (bW + sp), bW, cH, h, prevH, nextH), ActivityBarColor.copy(alpha = a))
        }
        if (si != null && si in 0 until n) {
            val x = si * (bW + sp) + bW / 2
            drawLine(ScrubLineColor, Offset(x, 0f), Offset(x, cH), 1.5.dp.toPx())
        }
        drawElapsedLabels(n, cH, tm)
    }
}

@Composable
internal fun WorkoutHrChart(
    summary: WorkoutSummary,
    scrub: ScrubState,
    tm: TextMeasurer,
    modifier: Modifier = Modifier,
) {
    val n = summary.stepsPerMinute.size
    val segments = remember(summary) { summary.hrChartSegments() }
    val si = scrub.scrubIndex

    Canvas(modifier.fillMaxWidth().scrubGesture(n, scrub)) {
        val cH = size.height - 14.dp.toPx()
        drawRect(ChartOverlayColor, Offset.Zero, Size(size.width, cH))
        if (n == 0 || summary.maxHr == null) return@Canvas

        val minBpm = (summary.hrPoints.minOf { it.bpm } - 10).coerceAtLeast(0)
        val range = (summary.maxHr + 10 - minBpm).coerceAtLeast(1).toFloat()
        // The x axis spans whole minutes from the minute the workout started in, matching the steps chart.
        val axisShift = summary.startTime % 60
        val axisSec = n * 60f
        fun toOffset(p: WorkoutHrPoint) =
            Offset((p.offsetSec + axisShift) / axisSec * size.width, cH - (p.bpm - minBpm) / range * cH)

        summary.avgHr?.let { avg ->
            val y = cH - (avg - minBpm) / range * cH
            drawLine(
                Color.White.copy(alpha = 0.4f), Offset(0f, y), Offset(size.width, y), 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())),
            )
        }
        for (segment in segments) {
            val pts = segment.map(::toOffset)
            if (pts.size == 1) {
                drawCircle(HRLineColor, 2.dp.toPx(), pts[0])
                continue
            }
            drawPath(smoothFilledPath(pts, cH), HRLineColor.copy(alpha = 0.2f))
            drawPath(smoothLinePath(pts), HRLineColor, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
        }
        if (si != null && si in 0 until n) {
            val x = (si + 0.5f) / n * size.width
            drawLine(ScrubLineColor, Offset(x, 0f), Offset(x, cH), 1.5.dp.toPx())
            summary.hrPerMinute.getOrNull(si)?.let { bpm ->
                val y = cH - (bpm - minBpm) / range * cH
                drawCircle(Color.White, 5.dp.toPx(), Offset(x, y))
                drawCircle(HRLineColor, 3.5.dp.toPx(), Offset(x, y))
            }
        }
        drawElapsedLabels(n, cH, tm)
    }
}

/** Elapsed-minute labels under a chart whose x axis spans [minutes] whole minutes. */
private fun DrawScope.drawElapsedLabels(minutes: Int, chartHeight: Float, tm: TextMeasurer) {
    val style = TextStyle(fontSize = 9.sp, color = AxisLabelColor)
    val step = ElapsedLabelSteps.firstOrNull { minutes / it < MAX_ELAPSED_LABELS } ?: ElapsedLabelSteps.last()
    for (m in 0 until minutes step step) {
        val label = tm.measure("$m'", style)
        val x = m.toFloat() / minutes * size.width
        if (x + label.size.width > size.width) break
        drawText(label, topLeft = Offset(x, chartHeight + 2.dp.toPx()))
    }
}
