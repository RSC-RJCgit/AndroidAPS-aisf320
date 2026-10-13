package app.aaps.ui.compose.overview.graphs

import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals

class SmbLabelSizeTest {

    @Test
    fun fullSizeAt500Dp() {
        assertEquals(9.6.sp, smbLabelSize(500f))
    }

    @Test
    fun halfSizeAt250Dp() {
        assertEquals(4.8.sp, smbLabelSize(250f))
    }

    @Test
    fun tallerThan500DpStaysFullSize() {
        assertEquals(9.6.sp, smbLabelSize(800f))
    }

    @Test
    fun unknownHeightStaysFullSize() {
        assertEquals(9.6.sp, smbLabelSize(0f))
    }
}
