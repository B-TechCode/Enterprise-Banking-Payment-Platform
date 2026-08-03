package com.payments.orch.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record AmountDto(
  @NotNull @DecimalMin(value="0.01") BigDecimal value,
  @NotBlank @Pattern(regexp="^[A-Z]{3}$") String currency
) {}
