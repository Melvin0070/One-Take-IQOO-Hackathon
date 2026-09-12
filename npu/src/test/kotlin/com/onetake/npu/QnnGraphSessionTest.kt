package com.onetake.npu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class QnnGraphSessionTest {
    @Test
    fun tensorSpecComputesCheckedByteCountFromShapeAndType() {
        val spec = QnnTensorSpec(
            name = "input_features",
            dataType = QnnDataType.FLOAT16,
            dimensions = listOf(1, 80, 3000),
        )

        assertEquals(480_000, spec.byteCount)
    }

    @Test
    fun inputValidationRejectsWrongCountAndByteCount() {
        val specs = listOf(
            QnnTensorSpec("input_ids", QnnDataType.INT32, listOf(1, 1)),
            QnnTensorSpec("position_ids", QnnDataType.INT32, listOf(1)),
        )

        assertThrows(IllegalArgumentException::class.java) {
            validateQnnInputs(specs, listOf(ByteArray(4)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateQnnInputs(specs, listOf(ByteArray(8), ByteArray(3)))
        }
    }

    @Test
    fun tensorSpecRejectsOverflowingOrNonPositiveShapes() {
        assertThrows(IllegalArgumentException::class.java) {
            QnnTensorSpec("invalid", QnnDataType.FLOAT32, listOf(0, 2))
        }
        assertThrows(IllegalArgumentException::class.java) {
            QnnTensorSpec("overflow", QnnDataType.FLOAT32, listOf(Long.MAX_VALUE, 2))
        }
    }

    @Test
    fun tensorSpecDimensionsCannotBeMutatedThroughMutableListView() {
        val spec = QnnTensorSpec("input", QnnDataType.FLOAT16, listOf(1, 2))

        assertThrows(UnsupportedOperationException::class.java) {
            (spec.dimensions as MutableList<Long>)[0] = 9L
        }
    }
}
