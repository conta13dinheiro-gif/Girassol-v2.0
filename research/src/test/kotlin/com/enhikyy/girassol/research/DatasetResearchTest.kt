package com.enhikyy.girassol.research

import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class DatasetResearchTest {
    private val intent=EpistemicCurriculumEngine().intent(1,12,"adao",Domain.PHYSICS,"Lacuna observada",mapOf(Domain.LOGIC to .8))
    private val card="""{"id":"science/qa","sha":"abc","downloads":200,"gated":false,"cardData":{"license":"cc-by-4.0","task_categories":["question-answering"]}}"""
    private val rows="""{"rows":[{"row_idx":2,"truncated_cells":[],"row":{"question":"What conserves energy?","answer":"An isolated system."}},{"row_idx":3,"truncated_cells":[0],"row":{"text":"truncated data must not be learned"}}]}"""
    @Test fun compactDownloadResumesWithRangeAndETagWithoutAnonymousFileBuffers() {
        MockWebServer().use { server->
            val directory=java.nio.file.Files.createTempDirectory("girassol-range").toFile()
            try {
                val target=java.io.File(directory,"science.jsonl")
                java.io.File(target.path+".part").writeText("first")
                java.io.File(target.path+".etag").writeText("v1")
                server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range","bytes 5-10/11").setHeader("ETag","v1").setBody("second"))
                val digest=ResilientHttp(sleeper={}).download(server.url("/file"),target,100)
                assertEquals("firstsecond",target.readText());assertEquals(sha256("firstsecond".toByteArray()),digest)
                val request=server.takeRequest();assertEquals("bytes=5-",request.getHeader("Range"));assertEquals("v1",request.getHeader("If-Range"))
                assertFalse(java.io.File(target.path+".part").exists())
            } finally { directory.deleteRecursively() }
        }
    }
    @Test fun resourceAdmissionUsesStrictGreenThresholdAndThermalLimit() {
        assertFalse(MemoryPressurePolicy.green(MemoryPressurePolicy.GREEN_BYTES,false,0))
        assertTrue(MemoryPressurePolicy.green(MemoryPressurePolicy.GREEN_BYTES+1,false,0))
        assertFalse(MemoryPressurePolicy.green(Long.MAX_VALUE,true,0))
        assertFalse(MemoryPressurePolicy.green(Long.MAX_VALUE,false,2))
        assertTrue(ResearchSettings(automatic=true,quarantine=false).valid())
        assertFalse(ResearchSettings(quotaMb=128).valid())
    }
    @Test fun dependencyGraphAndBilingualIntentAreStable() {
        val engine=EpistemicCurriculumEngine()
        assertEquals(Domain.LOGIC,engine.blocker(Domain.ENGINEERING,emptyMap()))
        assertEquals(intent,engine.intent(1,12,"adao",Domain.PHYSICS,"Lacuna observada",mapOf(Domain.LOGIC to .8)))
        assertTrue(intent.queryEn.contains("physics")); assertTrue(intent.queryPt.contains("física"))
    }
    @Test fun searchMetadataSplitsAndSurgicalRowsUseEncodedRequests() {
        MockWebServer().use { server->
            server.enqueue(MockResponse().setBody("""[{"id":"science/qa"}]""")); server.enqueue(MockResponse().setBody("[]"))
            server.enqueue(MockResponse().setBody(card))
            server.enqueue(MockResponse().setBody("""{"splits":[{"config":"default","split":"train"}]}"""))
            server.enqueue(MockResponse().setBody(rows))
            val seeker=HuggingFaceDatasetSeeker(ResilientHttp(),server.url("/"),server.url("/"))
            val found=seeker.discover(intent) { false }!!
            val batch=seeker.acquire(found.first,found.second,intent)
            assertEquals(1,batch.rows.size); assertEquals(2L,batch.rows.single().index)
            assertEquals(sha256(batch.raw),batch.payloadHash)
            val search=server.takeRequest().requestUrl!!
            assertEquals("20",search.queryParameter("limit")); assertEquals(intent.queryEn,search.queryParameter("search"))
            repeat(3) { server.takeRequest() }
            val request=server.takeRequest().requestUrl!!
            assertEquals("/search",request.encodedPath); assertEquals("100",request.queryParameter("length")); assertEquals("science/qa",request.queryParameter("dataset"))
        }
    }
    @Test fun unsupportedSearchFallsBackToRowsAndDoesNotRetry501() {
        MockWebServer().use { server->
            server.enqueue(MockResponse().setBody(card));server.enqueue(MockResponse().setResponseCode(501));server.enqueue(MockResponse().setBody(rows))
            val seeker=HuggingFaceDatasetSeeker(ResilientHttp(sleeper={}),server.url("/"),server.url("/"))
            val batch=seeker.acquire(seeker.inspect("science/qa")!!,DatasetSplit("default","train"),intent,100)
            assertTrue(batch.endpoint.contains("/rows?")); assertEquals(3,server.requestCount)
        }
    }
    @Test fun gatedUnlicensedAndRawMediaAreExcludedBeforeDownload() {
        MockWebServer().use { server->
            server.enqueue(MockResponse().setBody(card.replace("\"cc-by-4.0\"","\"unknown\"")))
            server.enqueue(MockResponse().setBody(card.replace("\"gated\":false","\"gated\":\"auto\"")))
            server.enqueue(MockResponse().setBody("""{"tags":["audio"],"cardData":{"license":"mit"}}"""))
            val seeker=HuggingFaceDatasetSeeker(ResilientHttp(),server.url("/"),server.url("/"))
            repeat(3) { assertNull(seeker.inspect("science/qa")) }
        }
    }
    @Test fun etagCacheAndRateLimitRespectPressureRevocation() {
        MockWebServer().use { server->
            server.enqueue(MockResponse().setHeader("ETag","v1").setBody("ok"));server.enqueue(MockResponse().setResponseCode(304))
            val http=ResilientHttp();assertEquals("ok",http.get(server.url("/cache")).bytes.decodeToString()); assertEquals("ok",http.get(server.url("/cache")).bytes.decodeToString())
            server.takeRequest();assertEquals("v1",server.takeRequest().getHeader("If-None-Match"))
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After","1"))
            var green=true
            val limited=ResilientHttp(gate=ResearchGate { if(!green) throw ResearchPaused("pressure") },sleeper={ green=false })
            try { limited.get(server.url("/rate"));fail("memory revocation ignored") } catch(_: ResearchPaused) { }
            assertEquals(3,server.requestCount)
        }
    }
    @Test fun journalRestoresOfflineAndRejectsTamperingAndOverflow() {
        val repo=DatasetRepo("science/qa","abc",1,"mit",listOf("question-answering"),1.0)
        val raw=rows.toByteArray();val batch=DatasetBatch(repo,DatasetSplit("default","train"),intent.queryHash,"https://datasets-server.huggingface.co/rows",0,sha256(raw),raw,emptyList())
        val sealed=KnowledgeJournal.seal(batch,12,"a".repeat(64),"0".repeat(64))
        assertArrayEquals(raw,KnowledgeJournal.restore(sealed))
        try { KnowledgeJournal.restore(sealed.copy(envelope=sealed.envelope.copy(tick=13)));fail("tamper ignored") } catch(_: IllegalArgumentException) { }
        try { KnowledgeJournal.restore(sealed.copy(compressed=byteArrayOf(1,2)));fail("corrupt compression ignored") } catch(_: IOException) { }
        assertEquals(listOf("old"),QuotaLru.victims(listOf(CacheEntry("new",20,2),CacheEntry("old",30,1),CacheEntry("pinned",10,0,true)),10,10,60))
        try { QuotaLru.victims(emptyList(),100,1,100);fail("journal evicted") } catch(_: ResearchPaused) { }
    }
    @Test fun crossValidationRequiresDistinctAgentsAndSameUntamperedRecord() {
        val row=DatasetRow(1,"A verified question and answer.",sha256("A verified question and answer.".toByteArray()))
        val first=KnowledgeReview("adao","a".repeat(64),row.digest,Verdict.APPROVE,95)
        val second=first.copy(agent="eva")
        assertTrue(EpistemicQuarantine.approved(row,first,second))
        assertFalse(EpistemicQuarantine.approved(row,first,first))
        assertFalse(EpistemicQuarantine.approved(row,first,second.copy(verdict=Verdict.UNCERTAIN)))
        assertFalse(EpistemicQuarantine.approved(row.copy(text="tampered"),first,second))
    }
}
