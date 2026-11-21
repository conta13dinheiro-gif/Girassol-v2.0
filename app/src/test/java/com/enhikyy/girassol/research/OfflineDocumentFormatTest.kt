package com.enhikyy.girassol.research

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OfflineDocumentFormatTest {
    @Test fun recognizesAllRequiredOfflineFormats() {
        assertEquals(OfflineDocumentFormat.TXT, OfflineDocumentFormat.fromName("notes.txt"))
        assertEquals(OfflineDocumentFormat.EPUB, OfflineDocumentFormat.fromName("library.epub"))
        assertEquals(OfflineDocumentFormat.PDF, OfflineDocumentFormat.fromName("paper.PDF"))
        assertEquals(OfflineDocumentFormat.PARQUET, OfflineDocumentFormat.fromName("citizens.parquet"))
    }

    @Test fun rejectsUnknownFormats() {
        assertThrows(IllegalArgumentException::class.java) { OfflineDocumentFormat.fromName("archive.bin") }
    }
}
