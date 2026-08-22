package com.lokesh_codes.expense_tracker_backend.service;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.lokesh_codes.expense_tracker_backend.DTO.PageResponse;
import com.lokesh_codes.expense_tracker_backend.DTO.TransactionDTO;
import com.lokesh_codes.expense_tracker_backend.DTO.TransactionFilter;
import com.lokesh_codes.expense_tracker_backend.DTO.TransactionMapping;
import com.lokesh_codes.expense_tracker_backend.entity.Category;
import com.lokesh_codes.expense_tracker_backend.entity.Transaction;
import com.lokesh_codes.expense_tracker_backend.entity.User;
import com.lokesh_codes.expense_tracker_backend.exception.NotFoundException;
import com.lokesh_codes.expense_tracker_backend.repository.TransactionRepository;

@Service
public class TransactionService {

    private final TransactionRepository transactionRepository;
    private final CategoryService categoryService;
    private final CurrentUserService currentUser;

    public TransactionService(TransactionRepository transactionRepository,
            CategoryService categoryService,
            CurrentUserService currentUser) {
        this.transactionRepository = transactionRepository;
        this.categoryService = categoryService;
        this.currentUser = currentUser;
    }

    @Transactional
    public TransactionDTO createTransaction(TransactionDTO dto) {
        User user = currentUser.require();
        Category category = categoryService.requireOwned(dto.getCategoryId());

        Transaction transaction = new Transaction();
        transaction.setUser(user);
        apply(transaction, dto, category);

        return TransactionMapping.toDTO(transactionRepository.save(transaction));
    }

    @Transactional
    public TransactionDTO updateTransaction(Integer id, TransactionDTO dto) {
        Transaction transaction = requireOwned(id);
        Category category = categoryService.requireOwned(dto.getCategoryId());
        apply(transaction, dto, category);
        return TransactionMapping.toDTO(transactionRepository.save(transaction));
    }

    @Transactional
    public void deleteTransaction(Integer id) {
        transactionRepository.delete(requireOwned(id));
    }

    @Transactional(readOnly = true)
    public TransactionDTO getTransactionById(Integer id) {
        return TransactionMapping.toDTO(requireOwned(id));
    }

    /** Filtered, sorted and paged search, evaluated in the database. */
    @Transactional(readOnly = true)
    public PageResponse<TransactionDTO> search(TransactionFilter filter, Pageable pageable) {
        Page<Transaction> page = transactionRepository.findAll(
                TransactionSpecifications.forUser(currentUser.requireId(), filter), pageable);
        return PageResponse.from(page, TransactionMapping::toDTO);
    }

    /**
     * Every matching transaction, unpaged. Used for CSV export and by the
     * dashboard, which aggregates over a whole period.
     */
    @Transactional(readOnly = true)
    public List<TransactionDTO> findAll(TransactionFilter filter) {
        return transactionRepository
                .findAll(TransactionSpecifications.forUser(currentUser.requireId(), filter))
                .stream()
                .map(TransactionMapping::toDTO)
                .toList();
    }

    private void apply(Transaction transaction, TransactionDTO dto, Category category) {
        transaction.setCategory(category);
        transaction.setDescription(dto.getDescription().trim());
        transaction.setAmount(dto.getAmount());
        transaction.setCurrency(dto.getCurrency().toUpperCase());
        transaction.setDate(dto.getDate());
        transaction.setPaymentMethod(dto.getPaymentMethod());
        transaction.setComments(dto.getComments());
    }

    private Transaction requireOwned(Integer id) {
        return transactionRepository.findByIdAndUser_Id(id, currentUser.requireId())
                .orElseThrow(() -> new NotFoundException("Transaction not found"));
    }
}
