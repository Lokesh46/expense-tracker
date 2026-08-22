package com.lokesh_codes.expense_tracker_backend.DTO;


import java.util.Date;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class TransactionDTO {

    private Integer id;
    private Integer userId;
    private Integer categoryId;
    private String description;
    private double amount;
    private String currency;
    private Date date;
    private String paymentMethod;
    private String comments;
}
