package com.buccaneer.browser

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch

class GeneralStability196Test {
    @Test fun tenThousandSnapshotsRetainOneCallbackAndLatestValue() {
        val queued = ArrayList<Runnable>(); val output = ArrayList<Int>()
        val q = UiCoalescer<Int>({ queued.add(it) }, { queued.remove(it) }) { output.add(it) }
        repeat(10000) { q.offer(it) }
        assertEquals(1, queued.size); queued.removeAt(0).run(); assertEquals(listOf(9999), output)
        q.offer(10000); assertEquals(1, queued.size); queued.removeAt(0).run(); assertEquals(listOf(9999,10000),output)
    }
    @Test fun countedBurstsPreserveTheFullCount() {
        val queued = ArrayList<Runnable>(); var total = 0
        val q = UiCoalescer<Int>({ queued.add(it) }, { queued.remove(it) }, { a,b -> a+b }) { total += it }
        repeat(10000) { q.offer(1) }; assertEquals(1,queued.size); queued.removeAt(0).run(); assertEquals(10000,total)
    }
    @Test fun reentrantOffersScheduleTheirOwnDrain() {
        val queued = ArrayList<Runnable>(); val output = ArrayList<Int>(); lateinit var q: UiCoalescer<Int>
        q = UiCoalescer({ queued.add(it) }, { queued.remove(it) }) { output.add(it); if(it==1)q.offer(2) }
        q.offer(1); queued.removeAt(0).run(); assertEquals(1,queued.size); queued.removeAt(0).run(); assertEquals(listOf(1,2),output)
    }
    @Test fun navigationCancellationDoesNotStrandTheNextDocument() {
        val queued = ArrayList<Runnable>(); val output = ArrayList<Int>()
        val q = UiCoalescer<Int>({ queued.add(it) }, { queued.remove(it) }) { output.add(it) }
        q.offer(1); q.cancel(); assertTrue(queued.isEmpty()); q.offer(2); queued.removeAt(0).run(); assertEquals(listOf(2),output)
    }
    @Test fun schedulerRejectionDoesNotRetainOrBlockFutureWork() {
        val queued = ArrayList<Runnable>(); var accept=false; val output=ArrayList<Int>()
        val q=UiCoalescer<Int>({ if(accept) queued.add(it) else false },{queued.remove(it)}){output.add(it)}
        q.offer(1); accept=true; q.offer(2); assertEquals(1,queued.size); queued.removeAt(0).run(); assertEquals(listOf(2),output)
    }
    @Test fun concurrentOffersStillCreateOnlyOneQueuedCallback() {
        val queued = java.util.Collections.synchronizedList(ArrayList<Runnable>()); var total=0
        val q=UiCoalescer<Int>({queued.add(it)},{queued.remove(it)},{a,b->a+b}){total+=it}
        val start=CountDownLatch(1); val ts=(1..8).map{Thread{start.await();repeat(2000){q.offer(1)}}.apply{start()}}
        start.countDown();ts.forEach{it.join()};assertEquals(1,queued.size);queued.removeAt(0).run();assertEquals(16000,total)
    }
    @Test fun nestingBoundaryRejectsPathologicalInputsWithoutRecursion() {
        assertTrue(JsonInputBudget.allows("[".repeat(32)+"0"+"]".repeat(32)))
        assertFalse(JsonInputBudget.allows("[".repeat(33)+"0"+"]".repeat(33)))
        assertFalse(JsonInputBudget.allows("[".repeat(100000)+"0"+"]".repeat(100000)))
    }
    @Test fun quotedBracketsEscapesAndLenientAndroidCommentsDoNotConsumeDepth() {
        assertTrue(JsonInputBudget.allows("{\"value\":\""+"[".repeat(200)+"\"}"))
        assertTrue(JsonInputBudget.allows("{'value':'"+"[".repeat(200)+"'}"))
        assertTrue(JsonInputBudget.allows("{\"v\":\"escaped \\\" [ \\\\ end\"}"))
        assertTrue(JsonInputBudget.allows("{/*"+"[".repeat(200)+"*/'v':1}"))
        assertTrue(JsonInputBudget.allows("{//"+"[".repeat(200)+"\n'v':1}"))
        assertTrue(JsonInputBudget.allows("{#"+"[".repeat(200)+"\n'v':1}"))
        assertFalse(JsonInputBudget.allows("{/* ignored */'v':"+"[".repeat(33)+"0"+"]".repeat(33)+"}"))
    }
}
