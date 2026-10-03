package dev.jobs.api;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** The dashboard's routes exist only in the browser; a reload of one is answered with the page. */
@Controller
class DashboardController {

    @GetMapping({"/jobs", "/schedules"})
    String dashboard() {
        return "forward:/index.html";
    }
}
