package com.flowreader.app.ui.screens.reader.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跨块选择模型测试（主计划第 9 节）。
 *
 * 核心命题：选择必须能**跨越段落边界**，且方向无关、结果不重复。
 */
class CrossBlockSelectionModelTest {

    private fun modelOf(vararg blocks: Pair<String, Int>) = CrossBlockSelectionModel().apply {
        blocks.forEach { (id, length) -> registerBlock(id, length) }
    }

    @Test
    fun movingEitherHandleKeepsTheOppositeBoundInForwardSelection() {
        val model = modelOf("a" to 20, "b" to 20)
        model.beginAt(CrossBlockSelectionModel.Point("a", 5))
        model.extendTo(CrossBlockSelectionModel.Point("b", 12))
        model.moveBoundary(true, CrossBlockSelectionModel.Point("a", 2))
        assertEquals(CrossBlockSelectionModel.Point("b", 12), model.focusPoint)
        model.moveBoundary(false, CrossBlockSelectionModel.Point("b", 18))
        assertEquals(2 until 20, model.rangeFor("a"))
        assertEquals(0 until 18, model.rangeFor("b"))
    }

    @Test
    fun movingEitherHandleKeepsTheOppositeBoundInReverseSelection() {
        val model = modelOf("a" to 20, "b" to 20)
        model.beginAt(CrossBlockSelectionModel.Point("b", 12))
        model.extendTo(CrossBlockSelectionModel.Point("a", 5))
        model.moveBoundary(true, CrossBlockSelectionModel.Point("a", 2))
        assertEquals(CrossBlockSelectionModel.Point("b", 12), model.anchorPoint)
        model.moveBoundary(false, CrossBlockSelectionModel.Point("b", 18))
        assertEquals(2 until 20, model.rangeFor("a"))
        assertEquals(0 until 18, model.rangeFor("b"))
    }

    @Test
    fun selectionWithinSingleBlockReturnsItsRange() {
        val model = modelOf("p0" to 20)
        model.beginAt(CrossBlockSelectionModel.Point("p0", 5))
        model.extendTo(CrossBlockSelectionModel.Point("p0", 12))
        assertEquals(5 until 12, model.rangeFor("p0"))
    }

    @Test
    fun forwardSelectionAcrossTwoBlocksCoversTailOfFirstAndHeadOfSecond() {
        val model = modelOf("p0" to 10, "p1" to 10)
        model.beginAt(CrossBlockSelectionModel.Point("p0", 6))
        model.extendTo(CrossBlockSelectionModel.Point("p1", 3))

        assertEquals("首块从锚点到块尾", 6 until 10, model.rangeFor("p0"))
        assertEquals("末块从块首到焦点", 0 until 3, model.rangeFor("p1"))
    }

    @Test
    fun middleBlocksAreFullySelected() {
        val model = modelOf("p0" to 10, "p1" to 8, "p2" to 10)
        model.beginAt(CrossBlockSelectionModel.Point("p0", 2))
        model.extendTo(CrossBlockSelectionModel.Point("p2", 4))

        assertEquals(2 until 10, model.rangeFor("p0"))
        assertEquals("中间的 p1 即使没被点到也必须整块选中", 0 until 8, model.rangeFor("p1"))
        assertEquals(0 until 4, model.rangeFor("p2"))
    }

    @Test
    fun backwardSelectionIsSymmetricToForwardSelection() {
        val forward = modelOf("p0" to 10, "p1" to 10)
        forward.beginAt(CrossBlockSelectionModel.Point("p0", 2))
        forward.extendTo(CrossBlockSelectionModel.Point("p1", 7))

        val backward = modelOf("p0" to 10, "p1" to 10)
        backward.beginAt(CrossBlockSelectionModel.Point("p1", 7))
        backward.extendTo(CrossBlockSelectionModel.Point("p0", 2))

        assertEquals("从下往上拖必须得到同一结果", forward.rangeFor("p0"), backward.rangeFor("p0"))
        assertEquals(forward.rangeFor("p1"), backward.rangeFor("p1"))
    }

    @Test
    fun blocksOutsideSelectionReturnNull() {
        val model = modelOf("p0" to 10, "p1" to 10, "p2" to 10)
        model.beginAt(CrossBlockSelectionModel.Point("p0", 2))
        model.extendTo(CrossBlockSelectionModel.Point("p1", 5))
        assertNull("p2 不在选择范围内", model.rangeFor("p2"))
    }

    @Test
    fun selectedBlocksAreInReadingOrder() {
        val model = modelOf("p0" to 10, "p1" to 10, "p2" to 10)
        model.beginAt(CrossBlockSelectionModel.Point("p0", 3))
        model.extendTo(CrossBlockSelectionModel.Point("p2", 2))
        assertEquals(listOf("p0", "p1", "p2"), model.selectedBlocks().map { it.first })
        assertEquals(3 until 10, model.selectedBlocks()[0].second)
        assertEquals(0 until 10, model.selectedBlocks()[1].second)
        assertEquals(0 until 2, model.selectedBlocks()[2].second)
    }

    @Test
    fun offsetsAreClampedToBlockLength() {
        val model = modelOf("p0" to 5)
        model.beginAt(CrossBlockSelectionModel.Point("p0", 999))
        model.extendTo(CrossBlockSelectionModel.Point("p0", 2))
        assertEquals(2 until 5, model.rangeFor("p0"))
    }

    @Test
    fun collapsedSelectionAtSamePointReturnsNull() {
        val model = modelOf("p0" to 10)
        model.beginAt(CrossBlockSelectionModel.Point("p0", 4))
        assertNull("锚点与焦点重合时没有可持久化内容", model.rangeFor("p0"))
    }

    @Test
    fun extendingWithoutBeginIsIgnored() {
        val model = modelOf("p0" to 10)
        model.extendTo(CrossBlockSelectionModel.Point("p0", 5))
        assertTrue(model.isEmpty)
        assertNull(model.rangeFor("p0"))
    }

    @Test
    fun unknownBlockIsRejected() {
        val model = modelOf("p0" to 10)
        model.beginAt(CrossBlockSelectionModel.Point("nope", 3))
        assertTrue("未登记的块不得成为锚点", model.isEmpty)
    }

    @Test
    fun reRegisteringDoesNotReorderBlocks() {
        // 重组时段落可能重复登记；顺序必须保持阅读顺序，否则中间块判断会错。
        val model = modelOf("p0" to 10, "p1" to 10, "p2" to 10)
        model.registerBlock("p0", 12)
        model.registerBlock("p1", 11)
        model.beginAt(CrossBlockSelectionModel.Point("p0", 1))
        model.extendTo(CrossBlockSelectionModel.Point("p2", 1))
        assertEquals(listOf("p0", "p1", "p2"), model.selectedBlocks().map { it.first })
        assertEquals("长度应更新为新值", 1 until 12, model.rangeFor("p0"))
    }

    @Test
    fun unregisteringAnchorBlockClearsSelection() {
        val model = modelOf("p0" to 10, "p1" to 10)
        model.beginAt(CrossBlockSelectionModel.Point("p0", 1))
        model.extendTo(CrossBlockSelectionModel.Point("p1", 5))
        model.unregisterBlock("p0")
        assertTrue("锚点块消失必须整体清空，避免悬空坐标", model.isEmpty)
        assertNull(model.rangeFor("p1"))
    }

    @Test
    fun clearRemovesSelection() {
        val model = modelOf("p0" to 10)
        model.beginAt(CrossBlockSelectionModel.Point("p0", 1))
        model.extendTo(CrossBlockSelectionModel.Point("p0", 5))
        model.clear()
        assertTrue(model.isEmpty)
        assertNull(model.rangeFor("p0"))
    }

    @Test
    fun zeroLengthBlockNeverProducesRange() {
        val model = modelOf("p0" to 10, "empty" to 0, "p1" to 10)
        model.beginAt(CrossBlockSelectionModel.Point("p0", 1))
        model.extendTo(CrossBlockSelectionModel.Point("p1", 1))
        assertNull("空块不得产出 0 until 0 这种无意义区间", model.rangeFor("empty"))
    }

    @Test
    fun reverseSelectionStartingInLaterBlockCoversCorrectHeadsAndTails() {
        // 从 p2/8 往回到 p0/3。把选择看成扁平文档上的区间才正确：
        //   p0=[0,10) p1=[10,20) p2=[20,30)
        //   起点侧 = p0/3 → 文档 3；终点侧 = p2/8 → 文档 28；选中 [3,28)
        // 拆回各块：p0 [3,10)、p1 [0,10)、p2 [0,8)。
        val model = modelOf("p0" to 10, "p1" to 10, "p2" to 10)
        model.beginAt(CrossBlockSelectionModel.Point("p2", 8))
        model.extendTo(CrossBlockSelectionModel.Point("p0", 3))

        assertEquals("p0 从区间起点选到块尾", 3 until 10, model.rangeFor("p0"))
        assertEquals("p1 整块落在区间内", 0 until 10, model.rangeFor("p1"))
        assertEquals("p2 从块首选到区间终点", 0 until 8, model.rangeFor("p2"))
    }
}
