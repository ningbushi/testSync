package com.syncplugins.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class InputMemory {
    public int schemaVersion = 2;
    public SetupDraft draft = new SetupDraft();
    public boolean draftPending;
    public long draftUpdatedAt;
    public List<String> repositoryPaths = new ArrayList<>();
    public List<String> remoteUrls = new ArrayList<>();
    public List<String> branches = new ArrayList<>();
    public List<String> privateKeyPaths = new ArrayList<>();
    public List<String> authorNames = new ArrayList<>();
    public List<String> authorEmails = new ArrayList<>();
}

