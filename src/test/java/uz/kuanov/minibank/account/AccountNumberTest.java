package uz.kuanov.minibank.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AccountNumberTest {

    @Test
    void padsSequenceValueTo20Digits() {
        assertThat(AccountNumber.fromSequenceValue(1)).isEqualTo("00000000000000000001");
        assertThat(AccountNumber.fromSequenceValue(123_456)).isEqualTo("00000000000000123456");
    }

    @Test
    void largestBigintStillFitsIn20Digits() {
        assertThat(AccountNumber.fromSequenceValue(Long.MAX_VALUE))
                .isEqualTo("09223372036854775807")
                .hasSize(AccountNumber.LENGTH);
    }

    @Test
    void rejectsNonPositiveValues() {
        assertThatThrownBy(() -> AccountNumber.fromSequenceValue(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AccountNumber.fromSequenceValue(-5)).isInstanceOf(IllegalArgumentException.class);
    }
}
