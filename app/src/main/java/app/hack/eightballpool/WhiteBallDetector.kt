package app.hack.eightballpool

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

data class DetectedWhiteBall(
    val x: Float,
    val y: Float,
    val radius: Float
)

object WhiteBallDetector {

    /**
     * High-precision, Edge-to-Edge White Cue Ball detector.
     * Stage 1: Integral-image warm-ivory white core search (rejects stripes, solids, hollow ghost ring, felt).
     * Stage 2: 2-pass sub-pixel centroid refinement.
     * Stage 3: 16-ray opposite-pair radial edge-to-edge boundary fit (ignores white aim line spikes).
     */
    fun detect(bitmap: Bitmap, table: TableBounds): DetectedWhiteBall? {
        val w = bitmap.width
        val h = bitmap.height
        val ballR = table.ballR
        if (ballR < 5f || w < 100 || h < 100) return null

        val x0 = max(0, table.left.toInt())
        val y0 = max(0, table.top.toInt())
        val x1 = min(w, table.right.toInt())
        val y1 = min(h, table.bottom.toInt())
        val roiW = x1 - x0
        val roiH = y1 - y0
        if (roiW < 40 || roiH < 40) return null

        // Bulk pixel copy is 15x faster than per-pixel bitmap.getPixel()
        val pixels = IntArray(roiW * roiH)
        bitmap.getPixels(pixels, 0, roiW, x0, y0, roiW, roiH)

        // Downsample stride for super-fast mobile scanning (~18ms on 2400x1080)
        val ds = max(1, (ballR / 9f).toInt())
        val gw = roiW / ds
        val gh = roiH / ds
        if (gw < 20 || gh < 20) return null
        val stride = gw + 1

        val iiW = IntArray((gh + 1) * stride)
        val iiC = IntArray((gh + 1) * stride)
        val iiD = IntArray((gh + 1) * stride)
        val iiF = IntArray((gh + 1) * stride)

        val maskW = BooleanArray(gw * gh)
        val maskF = BooleanArray(gw * gh)

        for (gy in 0 until gh) {
            val sy = gy * ds
            val rowBase = sy * roiW
            val iRow = (gy + 1) * stride
            val iPrev = gy * stride
            val mRow = gy * gw

            var sumW = 0
            var sumC = 0
            var sumD = 0
            var sumF = 0

            for (gx in 0 until gw) {
                val c = pixels[rowBase + gx * ds]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val mx = max(r, max(g, b))
                val mn = min(r, min(g, b))
                val diff = mx - mn

                val isFelt = (b > g) && (g > r + 20) && (b - g <= 65) && (r < 130)
                val isCueWhite = (mx >= 165) && (mn >= 135) && (diff * 4 <= mx) && (r >= b - 12)
                val isColor = !isFelt && (mx >= 65) && (diff * 10 >= mx * 3)
                val isDark = mx <= 70

                if (isCueWhite) {
                    sumW++
                    maskW[mRow + gx] = true
                }
                if (isColor) sumC++
                if (isDark) sumD++
                if (isFelt) {
                    sumF++
                    maskF[mRow + gx] = true
                }

                iiW[iRow + gx + 1] = iiW[iPrev + gx + 1] + sumW
                iiC[iRow + gx + 1] = iiC[iPrev + gx + 1] + sumC
                iiD[iRow + gx + 1] = iiD[iPrev + gx + 1] + sumD
                iiF[iRow + gx + 1] = iiF[iPrev + gx + 1] + sumF
            }
        }

        val rDs = ballR / ds
        val rg = max(3, (rDs * 0.72f).roundToInt())
        val rgOut = max(4, (rDs * 0.95f).roundToInt())
        val area = ((2 * rg + 1) * (2 * rg + 1)).toFloat()
        val areaOut = ((2 * rgOut + 1) * (2 * rgOut + 1)).toFloat()

        val ignoreX = (table.screenW * 0.27f).toInt()
        val ignoreY = (table.screenH * 0.28f).toInt()

        var bestScore = -1e9f
        var bestGx = -1
        var bestGy = -1

        fun rectSum(ii: IntArray, cx: Int, cy: Int, rad: Int): Int {
            val t = cy - rad
            val b = cy + rad + 1
            val l = cx - rad
            val r = cx + rad + 1
            return ii[b * stride + r] - ii[t * stride + r] - ii[b * stride + l] + ii[t * stride + l]
        }

        for (gy in rgOut until (gh - rgOut) step 2) {
            val screenY = y0 + gy * ds
            for (gx in rgOut until (gw - rgOut) step 2) {
                val screenX = x0 + gx * ds
                if (screenX < ignoreX && screenY < ignoreY) continue

                val wRatio = rectSum(iiW, gx, gy, rg) / area
                if (wRatio < 0.40f) continue

                // Reject hollow ghost circle (contains blue felt inside)
                val fRatio = rectSum(iiF, gx, gy, rg) / area
                if (fRatio > 0.24f) continue

                // Reject stripes & solid colored balls
                val cRatio = rectSum(iiC, gx, gy, rgOut) / areaOut
                if (cRatio > 0.14f) continue

                val dRatio = rectSum(iiD, gx, gy, rg) / area
                if (dRatio > 0.28f) continue

                val score = wRatio * 2.0f - cRatio * 6.0f - dRatio * 1.5f - fRatio * 2.0f
                if (score > bestScore) {
                    bestScore = score
                    bestGx = gx
                    bestGy = gy
                }
            }
        }

        if (bestGx < 0) return null

        // Stage 2: Full-resolution 2-pass sub-pixel centroid on white pixels
        var cx = (bestGx * ds).toFloat()
        var cy = (bestGy * ds).toFloat()
        val winR = (ballR * 1.08f).roundToInt().coerceAtLeast(4)
        val winRSq = winR * winR

        for (iter in 0 until 2) {
            var sx = 0f
            var sy = 0f
            var cnt = 0
            val ix = cx.roundToInt()
            val iy = cy.roundToInt()
            for (dy in -winR..winR) {
                val py = iy + dy
                if (py !in 0 until roiH) continue
                val rowOff = py * roiW
                for (dx in -winR..winR) {
                    if (dx * dx + dy * dy > winRSq) continue
                    val px = ix + dx
                    if (px !in 0 until roiW) continue
                    val c = pixels[rowOff + px]
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8) and 0xFF
                    val b = c and 0xFF
                    val mx = max(r, max(g, b))
                    val mn = min(r, min(g, b))
                    if (mx >= 165 && mn >= 135 && (mx - mn) * 4 <= mx && r >= b - 12) {
                        sx += px
                        sy += py
                        cnt++
                    }
                }
            }
            if (cnt > 12) {
                cx = sx / cnt
                cy = sy / cnt
            }
        }

        // Stage 3: 16-Ray Opposite-Pair Edge-to-Edge Boundary Fit (exact ball edges & radius!)
        val edgeDists = FloatArray(16)
        val steps = 28
        val startD = ballR * 0.45f
        val endD = ballR * 1.45f
        val stepSize = (endD - startD) / steps

        for (k in 0 until 16) {
            val ang = (k * 2.0 * PI / 16.0).toFloat()
            val dx = cos(ang)
            val dy = sin(ang)
            var foundD = endD
            for (s in 0..steps) {
                val d = startD + s * stepSize
                val px = (cx + dx * d).roundToInt()
                val py = (cy + dy * d).roundToInt()
                if (px !in 0 until roiW || py !in 0 until roiH) {
                    foundD = d
                    break
                }
                val c = pixels[py * roiW + px]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val isFelt = (b > g) && (g > r + 20) && (b - g <= 65) && (r < 130)
                if (isFelt) {
                    foundD = d - stepSize * 0.5f
                    break
                }
            }
            edgeDists[k] = foundD
        }

        val shiftXs = mutableListOf<Float>()
        val shiftYs = mutableListOf<Float>()
        val radii = mutableListOf<Float>()
        val minEdge = ballR * 0.72f
        val maxEdge = ballR * 1.28f

        for (k in 0 until 8) {
            val d1 = edgeDists[k]
            val d2 = edgeDists[k + 8]
            if (d1 in minEdge..maxEdge && d2 in minEdge..maxEdge) {
                val ang = (k * 2.0 * PI / 16.0).toFloat()
                val shift = (d1 - d2) * 0.5f
                shiftXs.add(cos(ang) * shift)
                shiftYs.add(sin(ang) * shift)
                radii.add((d1 + d2) * 0.5f)
            }
        }

        var finalR = ballR
        if (radii.size >= 3) {
            cx += median(shiftXs)
            cy += median(shiftYs)
            finalR = median(radii).coerceIn(ballR * 0.85f, ballR * 1.15f)
        }

        return DetectedWhiteBall(
            x = x0 + cx,
            y = y0 + cy,
            radius = finalR
        )
    }

    private fun median(values: List<Float>): Float {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) * 0.5f
    }
}
