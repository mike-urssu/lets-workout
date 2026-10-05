package testsupport

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/** Outside the application's component scan; imported only by tests that need an unexpected failure. */
@RestController
class FailingController {

    @GetMapping("/test/unexpected-error")
    fun fail(): Nothing = throw IllegalStateException("internal detail: db password=secret")
}
