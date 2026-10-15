package uz.kuanov.minibank.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AccountLockerTest {

    @Mock
    AccountRepository accountRepository;

    @InjectMocks
    AccountLocker accountLocker;

    @Test
    void locksInAscendingIdOrderWhateverTheArgumentOrder() {
        Account low = TestAccounts.customer(3, 7, Currency.UZS, "0");
        Account high = TestAccounts.system(9, Currency.UZS);
        when(accountRepository.findByIdForUpdate(3L)).thenReturn(Optional.of(low));
        when(accountRepository.findByIdForUpdate(9L)).thenReturn(Optional.of(high));

        Map<Long, Account> locked = accountLocker.lockInIdOrder(9, 3);

        InOrder order = inOrder(accountRepository);
        order.verify(accountRepository).findByIdForUpdate(3L);
        order.verify(accountRepository).findByIdForUpdate(9L);
        assertThat(locked).containsEntry(3L, low).containsEntry(9L, high);
    }

    @Test
    void locksEachAccountOnce() {
        when(accountRepository.findByIdForUpdate(3L))
                .thenReturn(Optional.of(TestAccounts.customer(3, 7, Currency.UZS, "0")));

        assertThat(accountLocker.lockInIdOrder(3, 3)).hasSize(1);
        verify(accountRepository, times(1)).findByIdForUpdate(3L);
    }

    @Test
    void missingAccountIsAnError() {
        when(accountRepository.findByIdForUpdate(3L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountLocker.lockInIdOrder(3)).isInstanceOf(IllegalStateException.class);
    }
}
