package dev.khanlabs.lanshare.transfer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChooseFilesTest {
    private val files = listOf(
        ChoiceFile("0", "a.txt", 100, alreadyHere = false),
        ChoiceFile("1", "b.txt", 200, alreadyHere = true),
        ChoiceFile("2", "c.txt", 400, alreadyHere = false),
        ChoiceFile("3", "d.txt", 800, alreadyHere = true),
    )

    @Test
    fun noPickCountsEveryFileAndSkipDropsTheOnesAlreadyHere() {
        assertEquals(Choice.Totals(4, 1500), Choice.totals(files, null, skip = false))
        assertEquals(Choice.Totals(2, 500), Choice.totals(files, null, skip = true))
    }

    @Test
    fun aPickCountsOnlyTheTickedFiles() {
        assertEquals(Choice.Totals(2, 500), Choice.totals(files, setOf("0", "2"), skip = false))
        assertEquals(Choice.Totals(2, 1000), Choice.totals(files, setOf("1", "3"), skip = false))
    }

    @Test
    fun aPickAndSkipTogetherLeaveOutTheTickedFilesThatAreAlreadyHere() {
        assertEquals(Choice.Totals(1, 400), Choice.totals(files, setOf("1", "2"), skip = true))
        assertEquals(Choice.Totals(0, 0), Choice.totals(files, setOf("1", "3"), skip = true))
    }

    @Test
    fun nothingTickedCountsNothing() {
        assertEquals(Choice.Totals(0, 0), Choice.totals(files, emptySet(), skip = false))
    }

    @Test
    fun everyFileTickedIsNoLimitAtAll() {
        assertNull(Choice.wantedFor(files, null))
        assertNull(Choice.wantedFor(files, setOf("0", "1", "2", "3")))
        assertNull(Choice.wantedFor(files, setOf("0", "1", "2", "3", "ghost")), "an unknown ID doesn't count")
    }

    @Test
    fun aPartialPickIsGivenToTheReceiverWithoutIdsItDoesNotKnow() {
        assertEquals(setOf("0", "2"), Choice.wantedFor(files, setOf("0", "2")))
        assertEquals(setOf("0"), Choice.wantedFor(files, setOf("0", "ghost")))
        assertEquals(emptySet(), Choice.wantedFor(files, emptySet()))
    }
}
