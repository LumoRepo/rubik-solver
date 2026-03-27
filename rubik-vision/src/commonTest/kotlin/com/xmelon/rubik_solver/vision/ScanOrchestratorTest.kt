package com.xmelon.rubik_solver.vision

import com.xmelon.rubik_solver.model.CubeColor
import com.xmelon.rubik_solver.model.Face
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScanOrchestratorTest {

    private lateinit var orchestrator: ScanOrchestrator

    @BeforeTest
    fun setUp() {
        orchestrator = ScanOrchestrator()
    }

    @Test
    fun `moveToPreviousFace returns false when already at first face`() {
        assertEquals(Face.D, orchestrator.currentFaceToScan.value)
        val moved = orchestrator.moveToPreviousFace()
        assertFalse(moved)
        assertEquals(Face.D, orchestrator.currentFaceToScan.value)
    }

    @Test
    fun `moveToPreviousFace returns true and moves back when not at first face`() {
        orchestrator.moveToNextFace()
        assertEquals(Face.L, orchestrator.currentFaceToScan.value)
        val moved = orchestrator.moveToPreviousFace()
        assertTrue(moved)
        assertEquals(Face.D, orchestrator.currentFaceToScan.value)
    }

    @Test
    fun `moveToPreviousFace clears isScanComplete`() {
        repeat(6) { orchestrator.moveToNextFace() }
        assertTrue(orchestrator.isScanComplete.value)
        val moved = orchestrator.moveToPreviousFace()
        assertTrue(moved)
        assertFalse(orchestrator.isScanComplete.value)
    }

    @Test
    fun `D-face orientation transform applied correctly in commitCurrentFace`() {
        // D is first in SCAN_ORDER [D,L,B,R,F,U], no navigation needed
        assertEquals(Face.D, orchestrator.currentFaceToScan.value)

        val colors = listOf(
            CubeColor.WHITE,  CubeColor.RED,    CubeColor.BLUE,
            CubeColor.GREEN,  CubeColor.YELLOW, CubeColor.ORANGE,
            CubeColor.WHITE,  CubeColor.RED,    CubeColor.BLUE
        )
        orchestrator.commitCurrentFace(colors)

        val facelets = orchestrator.scannedFacelets.value
        val offset = Face.D.offset

        assertEquals(colors[2].ordinal, facelets[offset + 0])
        assertEquals(colors[5].ordinal, facelets[offset + 1])
        assertEquals(colors[8].ordinal, facelets[offset + 2])
        assertEquals(colors[1].ordinal, facelets[offset + 3])
        assertEquals(colors[4].ordinal, facelets[offset + 4])
        assertEquals(colors[7].ordinal, facelets[offset + 5])
        assertEquals(colors[0].ordinal, facelets[offset + 6])
        assertEquals(colors[3].ordinal, facelets[offset + 7])
        assertEquals(colors[6].ordinal, facelets[offset + 8])
    }
}
