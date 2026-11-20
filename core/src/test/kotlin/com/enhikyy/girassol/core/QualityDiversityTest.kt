package com.enhikyy.girassol.core

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp

class QualityDiversityTest {
    private fun wire(): ByteBuffer {
        val bytes=ByteBuffer.allocateDirect(QualityDiversityArchive.WIRE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        bytes.putInt(0x31445147).putInt(1).putLong(3).putLong(7).putInt(1)
        repeat(16) { bytes.putDouble(0.0) };repeat(16) { bytes.putDouble(if(it==0) 1.0 else 0.0) };repeat(16) { bytes.putDouble(if(it==1) 1.0 else 0.0) }
        bytes.position(412+292*245);bytes.put(1.toByte()).putLong(45).putInt(1).putDouble(.7).putDouble(.5)
        repeat(16) { bytes.putDouble(when(it) { 0 -> .25;1 -> .5;else -> 0.0 }) }
        repeat(8) { bytes.putDouble(it/8.0) };bytes.putDouble(.5).putDouble(1/(1+exp(-.25))).putDouble(1/(1+exp(-.5)))
        bytes.clear();return bytes
    }
    @Test fun sparseArchiveValidatesProjectionAndRestoresEveryByte() {
        val raw=wire();val archive=QualityDiversityArchive.fromWire(raw)
        assertTrue(archive.valid(7));assertFalse(archive.valid(6));assertEquals((412+247)*2,archive.binary.length)
        val view=archive.view();assertEquals(3L,view.samples);assertEquals(1,view.occupied);assertEquals(1.0/512,view.coverage,0.0)
        assertEquals(.7,view.meanFitness(),0.0);assertEquals(List(8) { it/8.0 },view.policy(0))
        val restored=ByteBuffer.allocateDirect(raw.capacity());view.writeWire(restored,ByteArray(raw.capacity()))
        repeat(raw.capacity()) { assertEquals(raw.get(it),restored.get(it)) }
        assertEquals(archive,QualityDiversityArchive.fromWire(restored))
    }
    @Test fun corruptionRejectsMalformedStateAndIndexBeforeConsumption() {
        val original=wire()
        fun rejected(position: Int,value: Byte) {
            val previous=original.get(position);original.put(position,value)
            try { QualityDiversityArchive.fromWire(original);fail("bad byte $position accepted") } catch(expected: IllegalArgumentException) { }
            original.put(position,previous)
        }
        rejected(0,0);rejected(4,2);rejected(24,2);rejected(412+292*245,2)
        val archive=QualityDiversityArchive.fromWire(original)
        assertFalse(QualityDiversityArchive(archive.binary.dropLast(2)).valid(7))
        assertFalse(QualityDiversityArchive("f".repeat(300000)).valid(7))
        try { archive.view().policy(1);fail() } catch(expected: IllegalArgumentException) { }
    }
    @Test fun historicalCanonicalDefaultsAndNoveltyMetricRemainCompatible() {
        val world=WorldActor.genesis();val mind=MindState.initial(world,world.agents[0])
        assertFalse(Canonical.state(world).contains("qualityDiversity"))
        val old=List(300) { List(6) { .5 } };val legacy=mind.copy(behaviors=old)
        assertTrue(legacy.valid(0));assertEquals(300L,legacy.discoveredBehaviors)
        val bounded=legacy.copy(behaviors=old.takeLast(256),noveltyAdmissions=301)
        assertTrue(bounded.valid(0));assertEquals(301L,bounded.discoveredBehaviors)
        assertFalse(legacy.copy(noveltyAdmissions=300).valid(0))
        assertFalse(bounded.copy(noveltyAdmissions=255).valid(0))
        val restored=Canonical.state(Canonical.state(world.copy(minds=mapOf(world.agents[0].id to bounded))))
        assertEquals(bounded,restored.minds[world.agents[0].id])
    }
    @Test fun trajectoryUsesMeasuredBoundedDataAndCooperativeAxis() {
        val world=WorldActor.genesis();val citizen=world.agents[0]
        val values=BehavioralDescriptor.trajectory(world,citizen,MemoryBank(),.2)
        assertEquals(16,values.size);assertTrue(values.all { it.isFinite() && it in 0.0..1.0 });assertEquals(0.0,BehavioralDescriptor.cooperativeAxis(values),0.0)
        assertTrue(BehavioralDescriptor.fitness(citizen,.2)>BehavioralDescriptor.fitness(citizen,-.2))
        assertEquals(0.0,BehavioralDescriptor.cooperativeAxis(List(16) { 0.0 }),0.0)
        assertTrue(WorldActor.violations(world.copy(tick=7,qualityDiversity=QualityDiversityArchive.fromWire(wire()))).isEmpty())
    }
}
