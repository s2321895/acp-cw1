package com.example.acpcw1.dto;

import javax.validation.constraints.NotBlank;

public class UrlPathRequest {

    @NotBlank
    private String urlPath;

    public String getUrlPath() {
        return urlPath;
    }

    public void setUrlPath(String urlPath) {
        this.urlPath = urlPath;
    }
}
