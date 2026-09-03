package net.ukrhub.duty.template;

import net.ukrhub.duty.domain.DutyMark;
import net.ukrhub.duty.domain.RotationTemplate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RotationTemplateFormatTest {

    @Test
    void parsesNameAndPattern() {
        String content = """
                [ Name ]
                Чотири чергових + робочі дні

                [ Pattern ]
                D-WW
                WD-W
                WWD-
                -WWD
                """;

        RotationTemplate template = RotationTemplateFormat.parse(1, content);

        assertThat(template.id()).isEqualTo(1);
        assertThat(template.name()).isEqualTo("Чотири чергових + робочі дні");
        assertThat(template.slots()).isEqualTo(4);
        assertThat(template.period()).isEqualTo(4);
        assertThat(template.rows()).containsExactly("D-WW", "WD-W", "WWD-", "-WWD");
        assertThat(template.markAt(0, 0)).isEqualTo(DutyMark.DUTY);
        assertThat(template.markAt(0, 1)).isEqualTo(DutyMark.OFF);
        assertThat(template.markAt(0, 2)).isEqualTo(DutyMark.WORK);
    }

    @Test
    void serializeThenParseRoundTrips() {
        RotationTemplate original = new RotationTemplate(7, "Два чергових", List.of("DD--", "--DD"));

        RotationTemplate roundTripped = RotationTemplateFormat.parse(7, RotationTemplateFormat.serialize(original));

        assertThat(roundTripped).isEqualTo(original);
    }
}
