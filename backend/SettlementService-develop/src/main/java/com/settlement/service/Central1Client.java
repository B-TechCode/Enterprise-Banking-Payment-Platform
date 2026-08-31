package com.settlement.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class Central1Client {

    public String upload(String fileName) {

        log.info("Uploading Pain001 file={} to Central1...", fileName);

        return "CENTRAL1-" + System.currentTimeMillis();
    }
}