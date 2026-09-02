package com.company.skillplatform.adapter.infrastructure;
import org.springframework.stereotype.Component;
@Component public class OpenCodeAdapter extends AbstractZipAdapter {public String platformKey(){return "OPENCODE";}public String implementationKey(){return "opencode-adapter";}public String adapterVersion(){return "1.0.0";}}
