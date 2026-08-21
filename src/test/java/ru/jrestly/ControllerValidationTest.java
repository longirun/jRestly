package ru.jrestly;

import org.junit.jupiter.api.Test;
import ru.jrestly.fixtures.BrokenController;
import ru.jrestly.fixtures.TestController;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ControllerValidationTest {

    @Test
    void throwsOnGetWithFullProblemListByDefault() {
        JRestlyClient client = JRestlyClient.builder().baseUrl("http://localhost:1").build();

        ControllerValidationException e = assertThrows(ControllerValidationException.class,
                () -> client.get(BrokenController.class));

        assertEquals(BrokenController.class, e.getController());
        assertEquals(4, e.getProblems().size());
        assertTrue(e.getMessage().contains("noHttpAnnotation"));
        assertTrue(e.getMessage().contains("multipleHttpAnnotations"));
        assertTrue(e.getMessage().contains("multipartWithoutParams"));
        assertTrue(e.getMessage().contains("formWithoutParams"));
    }

    @Test
    void disabledValidationKeepsPerMethodFailureOnCall() {
        JRestlyClient client = JRestlyClient.builder().baseUrl("http://localhost:1")
                .validateControllers(false)
                .build();
        BrokenController broken = client.get(BrokenController.class);

        assertThrows(UnsupportedOperationException.class, broken::noHttpAnnotation);
    }

    @Test
    void validControllerPassesEagerValidation() {
        JRestlyClient client = JRestlyClient.builder().baseUrl("http://localhost:1").build();

        assertNotNull(client.get(TestController.class));
    }
}
