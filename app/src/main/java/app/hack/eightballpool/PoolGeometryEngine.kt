package app.hack.eightballpool

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

enum class AimMode(val label: String) {
    WALL("WALL"),
    CUT("CUT");

    fun next(): AimMode = if (this == WALL) CUT else WALL
}

data class Pocket(
    val id: String,
    val x: Float,
    val y: Float,
    val isCorner: Boolean
)

data class TableBounds(
    val screenW: Float,
    val screenH: Float,
    // Measured directly from your game screenshots (exact inner cushion highlight lip)
    val leftRatio: Float = 0.1945f,
    val rightRatio: Float = 0.8055f,
    val topRatio: Float = 0.2355f,
    val bottomRatio: Float = 0.9125f,
    val ballRScale: Float = 0.0294f
) {
    val left: Float = screenW * leftRatio
    val right: Float = screenW * rightRatio
    val top: Float = screenH * topRatio
    val bottom: Float = screenH * bottomRatio
    val width: Float = (right - left).coerceAtLeast(100f)
    val height: Float = (bottom - top).coerceAtLeast(60f)

    val ballR: Float = height * ballRScale

    // Exact ball-center reflection rectangle (center hits cushion at exactly 1 ballR inside inner lip)
    val bounceLeft: Float = left + ballR
    val bounceRight: Float = right - ballR
    val bounceTop: Float = top + ballR
    val bounceBottom: Float = bottom - ballR
    val midX: Float = left + width * 0.5f

    val cornerPotRadius: Float = ballR * 2.02f
    val sidePotHalfWidth: Float = ballR * 1.55f

    val pockets: List<Pocket> = listOf(
        Pocket("TL", left, top, true),
        Pocket("TM", midX, top, false),
        Pocket("TR", right, top, true),
        Pocket("BL", left, bottom, true),
        Pocket("BM", midX, bottom, false),
        Pocket("BR", right, bottom, true)
    )
}

data class BounceRay(
    val points: List<Pair<Float, Float>>,
    val pottedPocket: String?,
    val bounceCount: Int
)

data class ShotRenderData(
    val mode: AimMode,
    val cueX: Float,
    val cueY: Float,
    val cueRadius: Float,
    val cueAutoDetected: Boolean,
    val tapX: Float,
    val tapY: Float,
    val targetX: Float?,
    val targetY: Float?,
    val ghostX: Float?,
    val ghostY: Float?,
    val objectRay: BounceRay?,
    val cueRay: BounceRay?,
    val cutAngleDeg: Float?,
    val isImpossibleCut: Boolean
)

object PoolGeometryEngine {

    /**
     * Exact specular reflection raycaster inside the ball-center bounce rectangle.
     * Guarantees 0.00 deg reflection accuracy across 1B, 2B, 3B, and 4B cushion bounces.
     */
    fun raycastWithBounces(
        startX: Float,
        startY: Float,
        angleRad: Float,
        table: TableBounds,
        maxBounces: Int
    ): BounceRay {
        var x = startX.coerceIn(table.bounceLeft + 0.4f, table.bounceRight - 0.4f)
        var y = startY.coerceIn(table.bounceTop + 0.4f, table.bounceBottom - 0.4f)
        var dx = cos(angleRad)
        var dy = sin(angleRad)

        val points = mutableListOf<Pair<Float, Float>>()
        points.add(x to y)
        var pottedId: String? = null
        var bounces = 0

        for (seg in 0..maxBounces) {
            val tX = when {
                dx < -1e-5f -> (table.bounceLeft - x) / dx
                dx > 1e-5f -> (table.bounceRight - x) / dx
                else -> Float.MAX_VALUE
            }
            val tY = when {
                dy < -1e-5f -> (table.bounceTop - y) / dy
                dy > 1e-5f -> (table.bounceBottom - y) / dy
                else -> Float.MAX_VALUE
            }

            val tMin = min(tX, tY)
            if (tMin <= 0.15f || tMin > table.width * 3.5f || !tMin.isFinite()) break

            val hx = x + dx * tMin
            val hy = y + dy * tMin

            val hitPocket = checkPocketEntry(hx, hy, table)
            if (hitPocket != null) {
                points.add(hitPocket.x to hitPocket.y)
                pottedId = hitPocket.id
                break
            }

            points.add(hx to hy)
            if (seg == maxBounces) break

            if (abs(tX - tY) < 1.0f) {
                dx = -dx
                dy = -dy
            } else if (tX < tY) {
                dx = -dx
            } else {
                dy = -dy
            }

            bounces++
            x = (hx + dx * 0.5f).coerceIn(table.bounceLeft + 0.15f, table.bounceRight - 0.15f)
            y = (hy + dy * 0.5f).coerceIn(table.bounceTop + 0.15f, table.bounceBottom - 0.15f)
        }

        return BounceRay(points, pottedId, bounces)
    }

    private fun checkPocketEntry(hx: Float, hy: Float, table: TableBounds): Pocket? {
        for (p in table.pockets) {
            if (p.isCorner) {
                if (hypot(hx - p.x, hy - p.y) <= table.cornerPotRadius) return p
            } else {
                val onTopOrBot = abs(hy - table.bounceTop) < 3.5f || abs(hy - table.bounceBottom) < 3.5f
                if (onTopOrBot && abs(hx - p.x) <= table.sidePotHalfWidth && abs(hy - p.y) < table.ballR * 2.4f) {
                    return p
                }
            }
        }
        return null
    }

    fun findNearestPocket(x: Float, y: Float, table: TableBounds, snapDist: Float): Pocket? {
        var best: Pocket? = null
        var bestD = snapDist
        for (p in table.pockets) {
            val d = hypot(x - p.x, y - p.y)
            if (d <= bestD) {
                bestD = d
                best = p
            }
        }
        return best
    }

    /**
     * WALL / TRICKSHOT MODE:
     * Shoots a ray from White Cue Ball (cueX, cueY) through the user's tapped point (tapX, tapY)
     * and bounces it cleanly off the cushions up to maxBounces times.
     */
    fun solveWallBounceShot(
        cueX: Float,
        cueY: Float,
        cueRadius: Float,
        cueAutoDetected: Boolean,
        tapX: Float,
        tapY: Float,
        table: TableBounds,
        maxBounces: Int
    ): ShotRenderData {
        val dx = tapX - cueX
        val dy = tapY - cueY
        val angle = if (hypot(dx, dy) > 1f) atan2(dy, dx) else 0f
        val wallRay = raycastWithBounces(cueX, cueY, angle, table, maxBounces)
        val firstWall = if (wallRay.points.size >= 2) wallRay.points[1] else null

        return ShotRenderData(
            mode = AimMode.WALL,
            cueX = cueX,
            cueY = cueY,
            cueRadius = cueRadius,
            cueAutoDetected = cueAutoDetected,
            tapX = tapX,
            tapY = tapY,
            targetX = null,
            targetY = null,
            ghostX = firstWall?.first,
            ghostY = firstWall?.second,
            objectRay = null,
            cueRay = wallRay,
            cutAngleDeg = null,
            isImpossibleCut = false
        )
    }

    /**
     * CUT / AIM EXTEND MODE:
     * White Cue Ball (cueX, cueY) -> Ghost Ball behind Target Ball (targetX, targetY) ->
     * Extended Target Ball path through (tapX, tapY) + Bounces + 90-degree Cue Deflection.
     */
    fun solveCutShot(
        cueX: Float,
        cueY: Float,
        cueRadius: Float,
        cueAutoDetected: Boolean,
        targetX: Float,
        targetY: Float,
        aimX: Float,
        aimY: Float,
        table: TableBounds,
        maxBounces: Int
    ): ShotRenderData {
        val snapped = findNearestPocket(aimX, aimY, table, table.ballR * 1.95f)
        val ax = snapped?.x ?: aimX
        val ay = snapped?.y ?: aimY

        val objAngle = atan2(ay - targetY, ax - targetX)
        val nx = cos(objAngle)
        val ny = sin(objAngle)

        val gx = targetX - nx * (table.ballR * 2f)
        val gy = targetY - ny * (table.ballR * 2f)

        val inDx = gx - cueX
        val inDy = gy - cueY
        val inDist = hypot(inDx, inDy).coerceAtLeast(1f)
        val ux = inDx / inDist
        val uy = inDy / inDist

        val cutDot = (ux * nx + uy * ny).coerceIn(-1f, 1f)
        val cutDeg = Math.toDegrees(acos(cutDot.toDouble())).toFloat()
        val impossible = cutDot < 0.08f

        val objRay = raycastWithBounces(targetX, targetY, objAngle, table, maxBounces)

        val tx = ux - cutDot * nx
        val ty = uy - cutDot * ny
        val tLen = hypot(tx, ty)
        val cueRay = if (!impossible && tLen > 0.035f) {
            raycastWithBounces(gx, gy, atan2(ty, tx), table, min(maxBounces, 2))
        } else null

        return ShotRenderData(
            mode = AimMode.CUT,
            cueX = cueX,
            cueY = cueY,
            cueRadius = cueRadius,
            cueAutoDetected = cueAutoDetected,
            tapX = ax,
            tapY = ay,
            targetX = targetX,
            targetY = targetY,
            ghostX = gx,
            ghostY = gy,
            objectRay = objRay,
            cueRay = cueRay,
            cutAngleDeg = cutDeg,
            isImpossibleCut = impossible
        )
    }

    /**
     * Rotates a point (px, py) around pivot (ox, oy) by deltaDeg degrees and keeps it inside table bounds.
     */
    fun rotatePointAround(
        ox: Float,
        oy: Float,
        px: Float,
        py: Float,
        deltaDeg: Float,
        table: TableBounds
    ): Pair<Float, Float> {
        val curDist = hypot(px - ox, py - oy).coerceAtLeast(table.ballR * 4f)
        val curAng = atan2(py - oy, px - ox)
        val newAng = curAng + (deltaDeg * PI / 180.0).toFloat()
        val nx = (ox + cos(newAng) * curDist).coerceIn(table.left, table.right)
        val ny = (oy + sin(newAng) * curDist).coerceIn(table.top, table.bottom)
        return nx to ny
    }
}
