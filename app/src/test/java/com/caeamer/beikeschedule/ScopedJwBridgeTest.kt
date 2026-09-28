package com.caeamer.beikeschedule.import

import org.junit.Assert.*
import org.junit.Test

class ScopedJwBridgeTest {
    @Test fun `只接受本次请求的完整信封 重复取消和旧脚本均被隔离`() {
        val requests = AcademicRequests()
        requests.beginLogin(false)
        val id = requests.launch(AcademicTask.GRADES, 0)!!
        var writes = 0
        val bridge = ScopedJwBridge({ requests.accepts(AcademicTask.GRADES, it) }) { token ->
            object : JwBridge {
                override fun onError(message: String) { requests.finish(AcademicTask.GRADES, token, failed = true) }
                override fun onMessage(fn: String, args: List<String>) {
                    assertEquals("onGradesResult", fn)
                    assertEquals(listOf("cached"), args)
                    requests.saving(AcademicTask.GRADES, token)
                    writes++
                }
            }
        }
        bridge.dispatch("invalid")
        bridge.dispatch("""{"fn":"onGradesResult","args":["cached"]}""")
        bridge.dispatch("""{"requestId":"999","fn":"onError","args":["late"]}""")
        assertTrue(requests.accepts(AcademicTask.GRADES, id))
        val valid = """{"requestId":"$id","fn":"onGradesResult","args":["cached"]}"""
        bridge.dispatch(valid)
        bridge.dispatch(valid)
        requests.cancel(AcademicTask.GRADES)
        bridge.dispatch(valid)
        assertEquals(1, writes)
    }
}
