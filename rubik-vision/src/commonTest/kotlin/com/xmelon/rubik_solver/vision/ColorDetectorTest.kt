package com.xmelon.rubik_solver.vision

import com.xmelon.rubik_solver.model.CubeColor
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ColorDetectorTest {

    private lateinit var detector: ColorDetector

    @BeforeTest fun setup() { detector = ColorDetector() }

    private fun rgb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private val stdWhite  = rgb(240, 240, 230)
    private val stdYellow = rgb(200, 180, 20)
    private val stdRed    = rgb(180, 30, 30)
    private val stdOrange = rgb(200, 80, 10)
    private val stdBlue   = rgb(20, 50, 180)
    private val stdGreen  = rgb(20, 150, 50)

    @Test fun `priors classify standard Rubik colors correctly`() {
        assertEquals(CubeColor.WHITE,  detector.classify(LabConverter.sRgbToLab(stdWhite)).first)
        assertEquals(CubeColor.YELLOW, detector.classify(LabConverter.sRgbToLab(stdYellow)).first)
        assertEquals(CubeColor.RED,    detector.classify(LabConverter.sRgbToLab(stdRed)).first)
        assertEquals(CubeColor.ORANGE, detector.classify(LabConverter.sRgbToLab(stdOrange)).first)
        assertEquals(CubeColor.BLUE,   detector.classify(LabConverter.sRgbToLab(stdBlue)).first)
        assertEquals(CubeColor.GREEN,  detector.classify(LabConverter.sRgbToLab(stdGreen)).first)
    }

    @Test fun `confidence is in 0-1 range`() {
        val lab = LabConverter.sRgbToLab(stdRed)
        val (_, conf) = detector.classify(lab)
        assertTrue(conf >= 0f)
        assertTrue(conf <= 1f)
    }

    @Test fun `calibrateFace with 9 tiles shifts models toward confirmed colors`() {
        val blueLookingRgbs = IntArray(9) { stdBlue }
        val allYellow = List(9) { CubeColor.YELLOW }
        detector.calibrateFace(blueLookingRgbs, allYellow)
        val (color, _) = detector.classify(LabConverter.sRgbToLab(stdBlue))
        assertEquals(CubeColor.YELLOW, color)
    }

    @Test fun `calibrateTile with weight-3 affects classification quickly`() {
        detector.calibrateTile(stdOrange, CubeColor.BLUE)
        val lab = LabConverter.sRgbToLab(stdOrange)
        val blueScore  = detector.scoreFor(CubeColor.BLUE, lab)
        val greenScore = detector.scoreFor(CubeColor.GREEN, lab)
        assertTrue(blueScore < greenScore)
    }

    @Test fun `colorCycle advances through all 6 colors without repeating`() {
        val seen = mutableSetOf<CubeColor>()
        var cur = detector.classify(LabConverter.sRgbToLab(stdRed)).first
        repeat(6) {
            seen.add(cur)
            cur = detector.colorCycle(stdRed, cur)
        }
        assertEquals(6, seen.size)
    }

    @Test fun `saveCheckpoint and restoreCheckpoint rolls back model state`() {
        detector.saveCheckpoint()
        val garbageRgbs = IntArray(9) { rgb(128, 128, 128) }
        detector.calibrateFace(garbageRgbs, List(9) { CubeColor.RED })
        detector.restoreCheckpoint()
        assertEquals(CubeColor.WHITE, detector.classify(LabConverter.sRgbToLab(stdWhite)).first)
    }

    @Test fun `resetCalibration restores prior classification`() {
        val garbageRgbs = IntArray(9) { rgb(128, 128, 128) }
        detector.calibrateFace(garbageRgbs, List(9) { CubeColor.RED })
        detector.resetCalibration()
        assertEquals(CubeColor.WHITE, detector.classify(LabConverter.sRgbToLab(stdWhite)).first)
    }

    @Test fun `restoreCheckpoint is isolated — post-restore calibration does not corrupt snapshot`() {
        detector.saveCheckpoint()
        detector.restoreCheckpoint()
        val colorAfterFirstRestore = detector.classify(LabConverter.sRgbToLab(stdWhite)).first
        detector.saveCheckpoint()
        detector.calibrateFace(IntArray(9) { stdRed }, List(9) { CubeColor.WHITE })
        detector.restoreCheckpoint()
        val colorAfterSecondRestore = detector.classify(LabConverter.sRgbToLab(stdWhite)).first
        assertEquals(colorAfterFirstRestore, colorAfterSecondRestore)
    }

    @Test fun `actual Rubik pigments classify correctly with sufficient confidence`() {
        val pigments = listOf(
            CubeColor.WHITE  to floatArrayOf(92f,  -2f,   6f),
            CubeColor.YELLOW to floatArrayOf(80f,  -5f,  74f),
            CubeColor.RED    to floatArrayOf(39f,  63f,  50f),
            CubeColor.ORANGE to floatArrayOf(55f,  52f,  62f),
            CubeColor.BLUE   to floatArrayOf(26f,  18f, -55f),
            CubeColor.GREEN  to floatArrayOf(54f, -54f,  37f)
        )
        for ((expected, lab) in pigments) {
            val (color, confidence) = detector.classify(lab)
            assertEquals(expected, color)
            assertTrue(confidence >= 0.15f)
        }
    }

    @Test fun `RED tile at prior mean has confidence above centerStable threshold`() {
        val redPriorLab = floatArrayOf(39f, 63f, 50f)
        val (color, confidence) = detector.classify(redPriorLab)
        assertEquals(CubeColor.RED, color)
        assertTrue(confidence > 0.0f)
        assertTrue(confidence >= 0.15f)
    }

    @Test fun `modelDumpStr includes variance and n for all 6 colors`() {
        val s = detector.modelDumpStr()
        CubeColor.entries.forEach { c ->
            assertTrue(s.contains(c.name))
            assertTrue(s.contains("v="))
            assertTrue(s.contains("n="))
        }
    }

    @Test fun `rankFor returns 1 for best-matching prior color`() {
        val redRgb = LabConverter.labToSRgb(floatArrayOf(39f, 63f, 50f))
        assertEquals(1, detector.rankFor(redRgb, CubeColor.RED))
    }

    @Test fun `rankFor returns value in 1-6 range`() {
        val testRgbs = listOf(rgb(240, 235, 220), rgb(200, 20, 20), rgb(220, 80, 5))
        testRgbs.forEach { r ->
            CubeColor.entries.forEach { c ->
                val rank = detector.rankFor(r, c)
                assertTrue(rank >= 1)
                assertTrue(rank <= 6)
            }
        }
    }

    @Test fun `convergence — 54 samples converges all models`() {
        val realPigments = mapOf(
            CubeColor.WHITE  to rgb(235, 230, 215),
            CubeColor.YELLOW to rgb(210, 185, 15),
            CubeColor.RED    to rgb(185, 25, 25),
            CubeColor.ORANGE to rgb(205, 75, 8),
            CubeColor.BLUE   to rgb(15, 45, 190),
            CubeColor.GREEN  to rgb(15, 145, 45)
        )
        val faceColors = listOf(
            List(9) { CubeColor.WHITE },
            List(9) { CubeColor.YELLOW },
            List(9) { CubeColor.RED },
            List(9) { CubeColor.ORANGE },
            List(9) { CubeColor.BLUE },
            List(9) { CubeColor.GREEN }
        )
        for (colors in faceColors) {
            val rgbs = IntArray(9) { realPigments[colors[it]]!! }
            detector.calibrateFace(rgbs, colors)
        }
        for ((color, rgbVal) in realPigments) {
            val (classified, _) = detector.classify(LabConverter.sRgbToLab(rgbVal))
            assertEquals(color, classified)
        }
    }
}
