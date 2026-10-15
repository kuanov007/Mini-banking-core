package uz.kuanov.minibank.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import uz.kuanov.minibank.common.error.NotFoundException;
import uz.kuanov.minibank.common.security.CurrentUser;

/** Pure unit test: no Spring context, no database. */
@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    private static final CurrentUser CUSTOMER = new CurrentUser(7L, false);
    private static final CurrentUser ADMIN = new CurrentUser(1L, true);

    @Mock
    AccountRepository accountRepository;

    @InjectMocks
    AccountService accountService;

    @Test
    void openUsesTheNextSequenceValueAsAccountNumber() {
        when(accountRepository.nextAccountNumberValue()).thenReturn(42L);
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AccountResponse response = accountService.open(7L, Currency.USD);

        assertThat(response.number()).isEqualTo("00000000000000000042");
        assertThat(response.currency()).isEqualTo(Currency.USD);
        assertThat(response.status()).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    void customerLooksUpOnlyAmongOwnAccounts() {
        when(accountRepository.findByIdAndOwnerId(5L, 7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.get(5L, CUSTOMER))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Account with id 5 not found");
        verify(accountRepository, never()).findById(anyLong());
    }

    @Test
    void adminSeesAnyAccount() {
        when(accountRepository.findById(5L))
                .thenReturn(Optional.of(Account.openCustomerAccount("00000000000000000005", 7L, Currency.UZS)));

        assertThat(accountService.get(5L, ADMIN).number()).isEqualTo("00000000000000000005");
        verify(accountRepository, never()).findByIdAndOwnerId(anyLong(), anyLong());
    }

    @Test
    void changeStatusLocksTheRowBeforeUpdating() {
        Account account = Account.openCustomerAccount("00000000000000000005", 7L, Currency.UZS);
        when(accountRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(account));

        AccountResponse response = accountService.changeStatus(5L, AccountStatus.FROZEN);

        assertThat(response.status()).isEqualTo(AccountStatus.FROZEN);
        assertThat(account.getStatus()).isEqualTo(AccountStatus.FROZEN);
        verify(accountRepository, never()).findById(anyLong());
    }

    @Test
    void changeStatusOfMissingAccountIsNotFound() {
        when(accountRepository.findByIdForUpdate(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.changeStatus(99L, AccountStatus.FROZEN))
                .isInstanceOf(NotFoundException.class);
    }
}
