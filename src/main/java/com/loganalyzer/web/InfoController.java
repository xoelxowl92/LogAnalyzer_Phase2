package com.loganalyzer.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.util.Collections;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class InfoController {

    @Value("${spring.profiles.active:default}")
    private String activeProfile;

    @GetMapping("/profile")
    public Map<String, String> getProfile() {
        return Collections.singletonMap("profile", activeProfile);
    }

    @GetMapping("/setup/status")
    public Map<String, Boolean> getSetupStatus() {
        boolean completed = new File("config/setup.properties").exists();
        return Collections.singletonMap("completed", completed);
    }
}
