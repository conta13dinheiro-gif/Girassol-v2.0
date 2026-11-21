package com.enhikyy.girassol.research

/** Supported local formats are explicit; unknown extensions are never silently decoded as UTF-8. */
internal enum class OfflineDocumentFormat {
    TXT, EPUB, PDF, PARQUET;

    companion object {
        fun fromName(name: String): OfflineDocumentFormat = when(name.substringAfterLast('.', "").lowercase()) {
            "txt", "md", "markdown", "csv", "json" -> TXT
            "epub" -> EPUB
            "pdf" -> PDF
            "parquet" -> PARQUET
            else -> throw IllegalArgumentException("Formato offline não suportado: $name")
        }
    }
}
