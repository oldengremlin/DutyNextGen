package net.ukrhub.duty.exchange;

import net.ukrhub.duty.domain.DutyExchangeProposal;
import net.ukrhub.duty.domain.DutyExchangeStatus;
import net.ukrhub.duty.domain.DutyExchangeStep;
import net.ukrhub.duty.domain.DutyMark;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DutyExchangeFormatTest {

    @Test
    void parsesAllSections() {
        String content = """
                [ Initiator ] Кулинич А.
                [ InitiatorUsername ] kulinich
                [ Counterpart ] Журавльова К.
                [ Status ] PENDING
                [ CreatedAt ] 2026-09-04T15:30:00

                [ Steps ]
                DUTY 2026-09-07 2026-09-09
                WORK 2026-09-14 2026-09-21
                """;

        DutyExchangeProposal proposal = DutyExchangeFormat.parse(1, content);

        assertThat(proposal.id()).isEqualTo(1);
        assertThat(proposal.initiatorName()).isEqualTo("Кулинич А.");
        assertThat(proposal.initiatorUsername()).isEqualTo("kulinich");
        assertThat(proposal.counterpartName()).isEqualTo("Журавльова К.");
        assertThat(proposal.status()).isEqualTo(DutyExchangeStatus.PENDING);
        assertThat(proposal.createdAt()).isEqualTo(LocalDateTime.of(2026, 9, 4, 15, 30, 0));
        assertThat(proposal.steps()).containsExactly(
                new DutyExchangeStep(DutyMark.DUTY, LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 9)),
                new DutyExchangeStep(DutyMark.WORK, LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 21)));
    }

    @Test
    void serializeThenParseRoundTrips() {
        DutyExchangeProposal original = new DutyExchangeProposal(
                7, "Кулинич А.", "kulinich", "Журавльова К.",
                List.of(new DutyExchangeStep(DutyMark.DUTY, LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 9))),
                DutyExchangeStatus.ACCEPTED,
                LocalDateTime.of(2026, 9, 4, 15, 30, 0));

        DutyExchangeProposal roundTripped = DutyExchangeFormat.parse(7, DutyExchangeFormat.serialize(original));

        assertThat(roundTripped).isEqualTo(original);
    }
}
