package com.example.acpcw1.api;

import com.example.acpcw1.service.AcpDataService;
import com.example.acpcw1.service.ProcessService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AcpController.class)
class AcpControllerRouteTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AcpDataService dataService;

    @MockBean
    private ProcessService processService;

    @Test
    void copyContentUppercaseS3Route_isMapped() throws Exception {
        mockMvc.perform(post("/api/v1/acp/copy-content/S3/demo_table"))
                .andExpect(status().isOk());

        verify(dataService).copyPostgresToS3("demo_table");
    }

    @Test
    void copyContentLowercaseS3Route_isMapped() throws Exception {
        mockMvc.perform(post("/api/v1/acp/copy-content/s3/demo_table"))
                .andExpect(status().isOk());

        verify(dataService).copyPostgresToS3("demo_table");
    }
}
