package com.payments.orch.dto;

import jakarta.validation.constraints.*;
import java.util.Map;
import java.util.UUID;

public record BillPayRequest(
  @NotNull UUID debtorAccountId,
  @NotBlank String billerReferenceNumber,
  @NotBlank String invoiceReference,
  @NotBlank String executionDate,          // yyyy-MM-dd
  @NotNull  AmountDto amount,
  String note
 
) {}
