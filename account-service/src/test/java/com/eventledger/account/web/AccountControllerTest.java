package com.eventledger.account.web;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.eventledger.account.repo.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class AccountControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private TransactionRepository repository;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    private static String body(String eventId, String type, String amount) {
        return """
                {"eventId":"%s","type":"%s","amount":%s,"currency":"USD","eventTimestamp":"2026-05-15T10:00:00Z"}
                """.formatted(eventId, type, amount);
    }

    @Test
    void applyReturns201ThenDuplicateReturns200() throws Exception {
        mvc.perform(post("/accounts/acct-1/transactions").contentType(MediaType.APPLICATION_JSON)
                        .content(body("e1", "CREDIT", "100.00")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eventId", is("e1")));

        mvc.perform(post("/accounts/acct-1/transactions").contentType(MediaType.APPLICATION_JSON)
                        .content(body("e1", "CREDIT", "100.00")))
                .andExpect(status().isOk());

        mvc.perform(get("/accounts/acct-1/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance", is(100.00)));
    }

    @Test
    void rejectsZeroAmount() throws Exception {
        mvc.perform(post("/accounts/acct-1/transactions").contentType(MediaType.APPLICATION_JSON)
                        .content(body("bad", "CREDIT", "0")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.amount").exists());
    }

    @Test
    void rejectsMismatchedCurrencyWith422() throws Exception {
        // Establish the account currency as USD.
        mvc.perform(post("/accounts/acct-c/transactions").contentType(MediaType.APPLICATION_JSON)
                        .content(body("c1", "CREDIT", "100.00")))
                .andExpect(status().isCreated());

        String eur = """
                {"eventId":"c2","type":"DEBIT","amount":10,"currency":"EUR","eventTimestamp":"2026-05-15T11:00:00Z"}
                """;
        mvc.perform(post("/accounts/acct-c/transactions").contentType(MediaType.APPLICATION_JSON)
                        .content(eur))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void rejectsUnknownType() throws Exception {
        mvc.perform(post("/accounts/acct-1/transactions").contentType(MediaType.APPLICATION_JSON)
                        .content(body("bad", "TRANSFER", "10")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void accountDetailsReturnBalanceAndRecentTransactions() throws Exception {
        mvc.perform(post("/accounts/acct-d/transactions").contentType(MediaType.APPLICATION_JSON)
                .content(body("d1", "CREDIT", "100.00"))).andExpect(status().isCreated());
        mvc.perform(post("/accounts/acct-d/transactions").contentType(MediaType.APPLICATION_JSON)
                .content(body("d2", "DEBIT", "30.00"))).andExpect(status().isCreated());

        mvc.perform(get("/accounts/acct-d"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance", is(70.00)))
                .andExpect(jsonPath("$.transactionCount", is(2)))
                .andExpect(jsonPath("$.recentTransactions", hasSize(2)));
    }

    @Test
    void healthReportsUp() throws Exception {
        mvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("UP")))
                .andExpect(jsonPath("$.checks.database", is("UP")));
    }
}
