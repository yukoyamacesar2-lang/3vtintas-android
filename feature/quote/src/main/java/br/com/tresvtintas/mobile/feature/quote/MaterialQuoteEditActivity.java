package br.com.tresvtintas.mobile.feature.quote;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import br.com.tresvtintas.mobile.core.catalog.CatalogProduct;
import br.com.tresvtintas.mobile.core.catalog.CatalogException;
import br.com.tresvtintas.mobile.core.catalog.CatalogQuery;
import br.com.tresvtintas.mobile.core.customer.CustomerQuery;
import br.com.tresvtintas.mobile.core.customer.CustomerException;
import br.com.tresvtintas.mobile.core.customer.CustomerSummary;
import br.com.tresvtintas.mobile.core.quote.MaterialQuoteDetail;
import br.com.tresvtintas.mobile.core.quote.MaterialQuoteDraft;
import br.com.tresvtintas.mobile.core.quote.MaterialQuoteDraftLine;
import br.com.tresvtintas.mobile.core.quote.MaterialQuoteException;
import br.com.tresvtintas.mobile.core.quote.MaterialQuoteFailureKind;
import br.com.tresvtintas.mobile.core.quote.MaterialQuotePriceListOption;
import br.com.tresvtintas.mobile.core.quote.MaterialQuotePricingContext;
import br.com.tresvtintas.mobile.core.quote.MaterialQuotePreview;
import br.com.tresvtintas.mobile.core.quote.MaterialQuoteTintColor;
import br.com.tresvtintas.mobile.feature.quote.databinding.QuoteActivityEditBinding;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;

public final class MaterialQuoteEditActivity extends AppCompatActivity {
    private static final int LOOKUP_PAGE_SIZE = 100;
    private static final String EXTRA_QUOTE_ID =
            "br.com.tresvtintas.mobile.quote.EDIT_QUOTE_ID";
    private final List<CustomerSummary> customers = new ArrayList<>();
    private final List<CatalogProduct> products = new ArrayList<>();
    private final List<MaterialQuoteDraftLine> lines = new ArrayList<>();
    private QuoteActivityEditBinding binding;
    private ArrayAdapter<String> itemAdapter;
    private Optional<MaterialQuoteFeatureRuntime> runtime = Optional.empty();
    private Optional<CustomerSummary> selectedCustomer = Optional.empty();
    private MaterialQuotePricingState pricingState =
            MaterialQuotePricingState.legacy();
    private MaterialQuoteMutationAttempt mutationAttempt =
            new MaterialQuoteMutationAttempt();
    private long quoteId;
    private int revision;
    private long generation;
    private long lookupGeneration;
    private boolean initialized;
    private boolean lookupInProgress;
    private Optional<MaterialQuoteTintConfigurator> tintConfigurator =
            Optional.empty();
    private Optional<MaterialQuoteProductPicker> productPicker =
            Optional.empty();

    public static Intent intent(Context context, long quoteId) {
        return new Intent(context, MaterialQuoteEditActivity.class)
                .putExtra(EXTRA_QUOTE_ID, quoteId);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        MaterialQuotePrivacy.protect(this);
        EdgeToEdge.enable(this);
        binding = QuoteActivityEditBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        quoteId = getIntent().getLongExtra(EXTRA_QUOTE_ID, 0);
        binding.quoteEditToolbar.setTitle(
                quoteId > 0 ? R.string.quote_edit_title : R.string.quote_create_title);
        binding.quoteEditToolbar.setNavigationOnClickListener(ignored -> finish());
        itemAdapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_list_item_1,
                new ArrayList<>());
        binding.quoteItems.setAdapter(itemAdapter);
        binding.quoteCustomer.setOnClickListener(ignored -> chooseCustomer());
        binding.quotePriceList.setOnClickListener(ignored -> choosePriceList());
        binding.quoteProduct.setOnClickListener(ignored -> chooseProduct());
        binding.quoteTintProduct.setOnClickListener(ignored -> chooseTintProduct());
        binding.quoteItems.setOnItemClickListener(
                (parent, view, position, id) -> removeLine(position));
        binding.quoteSave.setOnClickListener(ignored -> save());
        restoreRetained();
    }

    @Override
    protected void onStart() {
        super.onStart();
        runtime = runtime();
        if (runtime.isEmpty() || !runtime.orElseThrow().draftWriteAllowed()) {
            showFailure();
            setEnabled(false);
            return;
        }
        MaterialQuoteFeatureRuntime available = runtime.orElseThrow();
        tintConfigurator = Optional.of(new MaterialQuoteTintConfigurator(
                this,
                available.quoteRepository(),
                available.workerExecutor(),
                new TintListener()));
        productPicker = Optional.of(new MaterialQuoteProductPicker(
                this,
                new MaterialQuoteProductPicker.Listener() {
                    @Override
                    public void onSearch(String search) {
                        lookupProducts(search);
                    }

                    @Override
                    public void onAdd(
                            CatalogProduct product,
                            String quantity) {
                        addLine(product, quantity);
                    }
                }));
        if (!initialized) {
            load();
        } else {
            setEnabled(true);
        }
    }

    @Override
    protected void onStop() {
        tintConfigurator.ifPresent(MaterialQuoteTintConfigurator::cancel);
        tintConfigurator = Optional.empty();
        productPicker.ifPresent(MaterialQuoteProductPicker::dismiss);
        productPicker = Optional.empty();
        generation++;
        lookupGeneration++;
        lookupInProgress = false;
        runtime = Optional.empty();
        super.onStop();
    }

    @Override
    public Object onRetainCustomNonConfigurationInstance() {
        if (!initialized) {
            return null;
        }
        return new RetainedEditor(
                selectedCustomer.orElse(null),
                List.copyOf(customers),
                List.copyOf(products),
                List.copyOf(lines),
                text(binding.quoteTitle),
                text(binding.quoteNotes),
                revision,
                pricingState,
                mutationAttempt.key(),
                mutationAttempt.fingerprint());
    }

    private void load() {
        generation++;
        long operation = generation;
        setEnabled(false);
        binding.quoteEditProgress.setVisibility(View.VISIBLE);
        binding.quoteEditError.setText(R.string.quote_loading);
        MaterialQuoteFeatureRuntime available = runtime.orElseThrow();
        available.workerExecutor().execute(() -> {
            try {
                MaterialQuoteDetail detail = quoteId > 0
                        ? available.quoteRepository().detail(quoteId)
                        : null;
                Optional<MaterialQuotePricingContext> pricing =
                        loadPricingContext(available);
                runOnUiThread(() -> initialize(
                        operation,
                        List.of(),
                        List.of(),
                        detail,
                        pricing));
            } catch (MaterialQuoteException exception) {
                runOnUiThread(() -> failLoad(operation, exception));
            }
        });
    }

    private void initialize(
            long operation,
            List<CustomerSummary> loadedCustomers,
            List<CatalogProduct> loadedProducts,
            MaterialQuoteDetail detail,
            Optional<MaterialQuotePricingContext> pricing) {
        if (operation != generation) {
            return;
        }
        customers.clear();
        customers.addAll(loadedCustomers);
        products.clear();
        products.addAll(loadedProducts);
        pricingState = pricing
                .map(MaterialQuotePricingState::from)
                .orElseGet(MaterialQuotePricingState::legacy);
        if (detail != null) {
            detail.pricing().priceListVersionPublicId()
                    .filter(pricingState::supports)
                    .ifPresent(version -> pricingState = pricingState.select(version));
            revision = detail.summary().revision();
            selectedCustomer = findCustomer(
                    loadedCustomers,
                    detail.summary().customer().id());
            if (selectedCustomer.isEmpty()) {
                selectedCustomer = Optional.of(summaryFromDetail(detail));
            }
            binding.quoteTitle.setText(detail.summary().title());
            binding.quoteNotes.setText(detail.notes().orElse(""));
            lines.clear();
            detail.items().forEach(item -> {
                if (item.productId().isPresent()) {
                    lines.add(new MaterialQuoteDraftLine(
                            item.productId().getAsLong(),
                            item.description(),
                            item.quantity(),
                            item.unitPrice(),
                            item.tint().map(value -> value.selection())));
                }
            });
        }
        initialized = true;
        binding.quoteEditProgress.setVisibility(View.INVISIBLE);
        binding.quoteEditError.setText("");
        render();
        setEnabled(true);
    }

    private static Optional<MaterialQuotePricingContext> loadPricingContext(
            MaterialQuoteFeatureRuntime available) throws MaterialQuoteException {
        if (available.organizationId().isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(available.quoteRepository().pricingContext(
                    available.organizationId().getAsLong()));
        } catch (MaterialQuoteException exception) {
            if (exception.kind() == MaterialQuoteFailureKind.PRICE_LIST_NOT_PUBLISHED
                    || exception.kind()
                            == MaterialQuoteFailureKind.PRICING_ENGINE_DISABLED) {
                return Optional.empty();
            }
            throw exception;
        }
    }

    private static Optional<CustomerSummary> findCustomer(
            List<CustomerSummary> values,
            long customerId) {
        return values.stream()
                .filter(customer -> customer.id() == customerId)
                .findFirst();
    }

    private static CustomerSummary summaryFromDetail(MaterialQuoteDetail detail) {
        return new CustomerSummary(
                detail.summary().customer().id(),
                detail.summary().organizationId(),
                detail.summary().customer().name(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                OptionalLong.empty(),
                detail.summary().createdAt(),
                detail.summary().updatedAt());
    }

    private void chooseCustomer() {
        if (quoteId > 0 || runtime.isEmpty() || lookupInProgress) {
            return;
        }
        EditText input = lookupInput(R.string.quote_customer_search_hint);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.quote_search_customer)
                .setView(input)
                .setPositiveButton(
                        R.string.quote_search_button,
                        (dialog, which) -> lookupCustomers(text(input)))
                .setNegativeButton(R.string.quote_cancel, null)
                .show();
    }

    private void choosePriceList() {
        if (!pricingState.visible() || !pricingState.selectable()) {
            return;
        }
        List<MaterialQuotePriceListOption> options = pricingState.context().options();
        String[] labels = options.stream()
                .map(option -> getString(
                        R.string.quote_price_list_value,
                        option.name(),
                        option.versionNumber()))
                .toArray(String[]::new);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.quote_select_price_list)
                .setItems(labels, (dialog, position) -> {
                    selectPriceList(options.get(position));
                })
                .setNegativeButton(R.string.quote_cancel, null)
                .show();
    }

    private void chooseProduct() {
        if (runtime.isEmpty() || lookupInProgress) {
            return;
        }
        if (!pricingState.ready()) {
            binding.quoteEditError.setText(R.string.quote_price_list_required);
            return;
        }
        productPicker.ifPresent(MaterialQuoteProductPicker::show);
    }

    private void chooseTintProduct() {
        MaterialQuoteFeatureRuntime available = runtime.orElse(null);
        if (available == null || lookupInProgress
                || available.organizationId().isEmpty()) {
            showFailure();
            return;
        }
        Optional<br.com.tresvtintas.mobile.core.quote.MaterialQuotePricingSelection>
                pricing = pricingState.claim();
        if (pricing.isEmpty()) {
            binding.quoteEditError.setText(R.string.quote_price_list_required);
            return;
        }
        tintConfigurator.ifPresent(value -> value.start(
                available.organizationId().getAsLong(),
                pricing.orElseThrow()));
    }

    private void selectPriceList(MaterialQuotePriceListOption option) {
        if (pricingState.selectedOption()
                .map(value -> value.versionPublicId().equals(option.versionPublicId()))
                .orElse(false)) {
            return;
        }
        if (lines.isEmpty()) {
            applyPriceList(option);
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.quote_change_price_list_title)
                .setMessage(R.string.quote_change_price_list_warning)
                .setPositiveButton(
                        R.string.quote_change_price_list_confirm,
                        (dialog, which) -> {
                            lines.clear();
                            applyPriceList(option);
                            render();
                        })
                .setNegativeButton(R.string.quote_cancel, null)
                .show();
    }

    private void applyPriceList(MaterialQuotePriceListOption option) {
        pricingState = pricingState.select(option.versionPublicId());
        mutationAttempt.reset();
        renderPricing();
    }

    private EditText lookupInput(int hint) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(hint);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(120)});
        return input;
    }

    private void lookupCustomers(String search) {
        MaterialQuoteFeatureRuntime available = runtime.orElse(null);
        if (available == null) {
            showFailure();
            return;
        }
        long operation = beginLookup();
        available.workerExecutor().execute(() -> {
            try {
                List<CustomerSummary> found =
                        available.customerRepository().page(
                                new CustomerQuery(
                                        Optional.of(search),
                                        LOOKUP_PAGE_SIZE),
                                Optional.empty()).items();
                List<CustomerSummary> scoped =
                        MaterialQuoteCustomerScope.forOrganization(
                                found,
                                available.organizationId());
                runOnUiThread(() -> showCustomers(operation, scoped));
            } catch (CustomerException exception) {
                runOnUiThread(() -> failLookup(operation));
            }
        });
    }

    private void lookupProducts(String search) {
        MaterialQuoteFeatureRuntime available = runtime.orElse(null);
        if (available == null) {
            showFailure();
            return;
        }
        long operation = beginLookup();
        productPicker.ifPresent(MaterialQuoteProductPicker::renderLoading);
        available.workerExecutor().execute(() -> {
            try {
                pricingState.selectedOption().ifPresent(option -> {
                    try {
                        available.catalogRepository().selectPriceList(
                                option.versionPublicId());
                    } catch (CatalogException exception) {
                        throw new CatalogLookupFailure(exception);
                    }
                });
                List<CatalogProduct> found =
                        available.catalogRepository().refresh(
                                new CatalogQuery(
                                        Optional.of(search),
                                        OptionalLong.empty(),
                                        LOOKUP_PAGE_SIZE)).items();
                runOnUiThread(() -> showProducts(operation, found));
            } catch (CatalogException | CatalogLookupFailure exception) {
                runOnUiThread(() -> failProductLookup(operation));
            }
        });
    }

    private long beginLookup() {
        lookupInProgress = true;
        lookupGeneration++;
        setEnabled(false);
        binding.quoteEditProgress.setVisibility(View.VISIBLE);
        binding.quoteEditError.setText(R.string.quote_loading);
        return lookupGeneration;
    }

    private void showCustomers(
            long operation,
            List<CustomerSummary> found) {
        if (!finishLookup(operation)) {
            return;
        }
        customers.clear();
        customers.addAll(found);
        if (customers.isEmpty()) {
            binding.quoteEditError.setText(R.string.quote_customer_empty);
            return;
        }
        String[] labels = customers.stream()
                .map(CustomerSummary::name)
                .toArray(String[]::new);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.quote_select_customer)
                .setItems(labels, (dialog, which) -> {
                    selectedCustomer = Optional.of(customers.get(which));
                    mutationAttempt.reset();
                    render();
                })
                .setNegativeButton(R.string.quote_cancel, null)
                .show();
    }

    private void showProducts(
            long operation,
            List<CatalogProduct> found) {
        if (!finishLookup(operation)) {
            return;
        }
        products.clear();
        products.addAll(found);
        productPicker.ifPresent(value -> value.renderResults(products));
    }

    private void failProductLookup(long operation) {
        if (finishLookup(operation)) {
            productPicker.ifPresent(MaterialQuoteProductPicker::renderFailure);
        }
    }

    private void failLookup(long operation) {
        if (finishLookup(operation)) {
            binding.quoteEditError.setText(R.string.quote_failure);
        }
    }

    private boolean finishLookup(long operation) {
        if (operation != lookupGeneration) {
            return false;
        }
        lookupInProgress = false;
        binding.quoteEditProgress.setVisibility(View.INVISIBLE);
        binding.quoteEditError.setText("");
        setEnabled(true);
        return true;
    }

    private void askTintQuantity(MaterialQuoteTintColor color) {
        EditText input = new EditText(this);
        input.setInputType(
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setText(R.string.quote_default_quantity);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.quote_quantity_title)
                .setView(input)
                .setPositiveButton(R.string.quote_add, (dialog, which) ->
                        addTintLine(color, text(input)))
                .setNegativeButton(R.string.quote_cancel, null)
                .show();
    }

    private void addLine(CatalogProduct product, String rawQuantity) {
        try {
            BigDecimal quantity = new BigDecimal(rawQuantity.replace(',', '.'));
            MaterialQuoteDraftLine line = new MaterialQuoteDraftLine(
                    product.id(),
                    product.name(),
                    quantity,
                    product.price().orElseThrow());
            lines.removeIf(current -> current.identityKey().equals(line.identityKey()));
            lines.add(line);
            mutationAttempt.reset();
            render();
        } catch (IllegalArgumentException exception) {
            binding.quoteEditError.setText(R.string.quote_invalid);
        }
    }

    private void addTintLine(MaterialQuoteTintColor color, String rawQuantity) {
        try {
            BigDecimal quantity = new BigDecimal(rawQuantity.replace(',', '.'));
            MaterialQuoteDraftLine line = new MaterialQuoteDraftLine(
                    color.productId(),
                    color.productName(),
                    quantity,
                    color.amount(),
                    Optional.of(color.selection()));
            lines.removeIf(current ->
                    current.identityKey().equals(line.identityKey()));
            lines.add(line);
            mutationAttempt.reset();
            render();
        } catch (IllegalArgumentException exception) {
            binding.quoteEditError.setText(R.string.quote_invalid);
        }
    }

    private void removeLine(int position) {
        if (position >= 0 && position < lines.size()) {
            lines.remove(position);
            mutationAttempt.reset();
            render();
        }
    }

    private void render() {
        binding.quoteCustomer.setText(selectedCustomer
                .map(CustomerSummary::name)
                .orElse(getString(R.string.quote_select_customer)));
        renderPricing();
        itemAdapter.clear();
        NumberFormat currency = NumberFormat.getCurrencyInstance(
                new Locale("pt", "BR"));
        lines.forEach(line -> itemAdapter.add(
                line.productName()
                        + line.tint().map(value -> "\n" + getString(
                                R.string.quote_tint_line_detail,
                                value.colorName(),
                                value.lineName(),
                                value.finishName(),
                                value.packageName())).orElse("")
                        + "\n"
                        + line.quantity().toPlainString()
                        + " × "
                        + currency.format(line.displayedUnitPrice())
                        + " = "
                        + currency.format(line.estimate())
                        + " • toque para remover"));
        BigDecimal estimate = lines.stream()
                .map(MaterialQuoteDraftLine::estimate)
                .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
        binding.quoteEstimate.setText(getString(
                R.string.quote_total_estimate,
                estimate.toPlainString()));
    }

    private void renderPricing() {
        binding.quotePriceList.setVisibility(
                pricingState.visible() ? View.VISIBLE : View.GONE);
        binding.quotePriceList.setText(pricingState.selectedOption()
                .map(option -> getString(
                        R.string.quote_price_list_value,
                        option.name(),
                        option.versionNumber()))
                .orElse(getString(R.string.quote_select_price_list)));
    }

    private void save() {
        if (runtime.isEmpty() || selectedCustomer.isEmpty() || lines.isEmpty()) {
            binding.quoteEditError.setText(R.string.quote_invalid);
            return;
        }
        MaterialQuoteFeatureRuntime available = runtime.orElseThrow();
        if (pricingState.visible()
                && (!available.organizationId().isPresent()
                        || selectedCustomer.orElseThrow().organizationId().isEmpty()
                        || selectedCustomer.orElseThrow().organizationId().getAsLong()
                                != available.organizationId().getAsLong())) {
            binding.quoteEditError.setText(R.string.quote_customer_store_required);
            return;
        }
        if (!pricingState.ready()) {
            binding.quoteEditError.setText(R.string.quote_price_list_required);
            return;
        }
        MaterialQuoteDraft draft;
        try {
            CustomerSummary customer = selectedCustomer.orElseThrow();
            draft = new MaterialQuoteDraft(
                    customer.id(),
                    customer.name(),
                    Optional.of(text(binding.quoteTitle)),
                    Optional.of(text(binding.quoteNotes)),
                    Optional.empty(),
                    pricingState.claim(),
                    lines);
        } catch (IllegalArgumentException exception) {
            binding.quoteEditError.setText(R.string.quote_invalid);
            return;
        }
        generation++;
        long operation = generation;
        setEnabled(false);
        binding.quoteEditProgress.setVisibility(View.VISIBLE);
        available.workerExecutor().execute(() -> previewRemote(
                operation,
                available,
                draft));
    }

    private void previewRemote(
            long operation,
            MaterialQuoteFeatureRuntime available,
            MaterialQuoteDraft draft) {
        try {
            MaterialQuotePreview preview = quoteId > 0
                    ? available.quoteRepository().previewUpdate(
                        quoteId,
                        revision,
                        draft)
                    : available.quoteRepository().previewCreate(draft);
            runOnUiThread(() -> showPreview(
                    operation,
                    available,
                    draft,
                    preview));
        } catch (MaterialQuoteException exception) {
            runOnUiThread(() -> finishFailure(operation, exception));
        }
    }

    private void showPreview(
            long operation,
            MaterialQuoteFeatureRuntime available,
            MaterialQuoteDraft draft,
            MaterialQuotePreview preview) {
        if (operation != generation) {
            return;
        }
        binding.quoteEditProgress.setVisibility(View.INVISIBLE);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.quote_preview_title)
                .setMessage(previewMessage(preview))
                .setNegativeButton(
                        R.string.quote_cancel,
                        (dialog, which) -> cancelPreview(operation))
                .setPositiveButton(
                        R.string.quote_preview_confirm,
                        (dialog, which) -> confirmPreview(
                                operation,
                                available,
                                draft,
                                preview))
                .setOnCancelListener(dialog -> cancelPreview(operation))
                .show();
    }

    private String previewMessage(MaterialQuotePreview preview) {
        NumberFormat currency = NumberFormat.getCurrencyInstance(
                new Locale("pt", "BR"));
        StringBuilder result = new StringBuilder()
                .append(getString(
                        R.string.quote_preview_customer,
                        preview.customerName()))
                .append('\n')
                .append(preview.pricing().resolved()
                        ? getString(
                                R.string.quote_preview_table,
                                preview.pricing().priceListName().orElseThrow(),
                                preview.pricing().versionNumber().orElseThrow())
                        : getString(R.string.quote_preview_legacy))
                .append("\n\n");
        preview.items().forEach(item -> result
                .append(getString(
                        R.string.quote_preview_line,
                        item.description(),
                        item.quantity().toPlainString(),
                        item.unit(),
                        currency.format(item.unitPrice()),
                        currency.format(item.total())))
                .append("\n\n"));
        result.append(getString(
                R.string.quote_preview_subtotal,
                currency.format(preview.subtotal())))
                .append('\n');
        if (preview.discount().signum() > 0) {
            result.append(getString(
                    R.string.quote_preview_discount,
                    currency.format(preview.discount())))
                    .append('\n');
        }
        return result.append(getString(
                        R.string.quote_preview_total,
                        currency.format(preview.total())))
                .append("\n\n")
                .append(getString(R.string.quote_preview_note))
                .toString();
    }

    private void cancelPreview(long operation) {
        if (operation == generation) {
            setEnabled(true);
        }
    }

    private void confirmPreview(
            long operation,
            MaterialQuoteFeatureRuntime available,
            MaterialQuoteDraft draft,
            MaterialQuotePreview preview) {
        if (operation != generation) {
            return;
        }
        String key = mutationAttempt.keyFor(
                draft,
                revision,
                preview.fingerprint());
        binding.quoteEditProgress.setVisibility(View.VISIBLE);
        available.workerExecutor().execute(() -> saveRemote(
                operation,
                available,
                draft,
                preview.fingerprint(),
                key));
    }

    private void saveRemote(
            long operation,
            MaterialQuoteFeatureRuntime available,
            MaterialQuoteDraft draft,
            String expectedPreviewFingerprint,
            String key) {
        try {
            if (quoteId > 0) {
                available.quoteRepository().update(
                        quoteId,
                        revision,
                        draft,
                        expectedPreviewFingerprint,
                        key);
            } else {
                available.quoteRepository().create(
                        draft,
                        expectedPreviewFingerprint,
                        key);
            }
            runOnUiThread(() -> finishSuccess(operation));
        } catch (MaterialQuoteException exception) {
            runOnUiThread(() -> finishFailure(operation, exception));
        }
    }

    private void finishSuccess(long operation) {
        if (operation == generation) {
            setResult(RESULT_OK);
            finish();
        }
    }

    private void finishFailure(
            long operation,
            MaterialQuoteException exception) {
        if (operation != generation) {
            return;
        }
        binding.quoteEditProgress.setVisibility(View.INVISIBLE);
        setEnabled(true);
        if (!isTransient(exception.kind())) {
            mutationAttempt.reset();
        }
        if (exception.kind() == MaterialQuoteFailureKind.PRICE_POLICY_CHANGED
                || exception.kind()
                        == MaterialQuoteFailureKind.PRICE_LIST_SELECTION_REQUIRED) {
            pricingState = MaterialQuotePricingState.legacy();
            initialized = false;
            binding.quoteEditError.setText(R.string.quote_price_policy_changed);
            load();
            return;
        }
        if (exception.kind() == MaterialQuoteFailureKind.PRICE_NOT_AVAILABLE
                || exception.kind() == MaterialQuoteFailureKind.TINT_MAPPING_PENDING) {
            binding.quoteEditError.setText(R.string.quote_price_resolution_failure);
        } else {
            binding.quoteEditError.setText(R.string.quote_failure);
        }
    }

    private void failLoad(long operation, MaterialQuoteException exception) {
        if (operation == generation) {
            binding.quoteEditProgress.setVisibility(View.INVISIBLE);
            String detail = getString(R.string.quote_failure);
            binding.quoteEditError.setText(
                    exception.requestId()
                            .map(requestId -> getString(
                                    R.string.quote_failure_with_support,
                                    detail,
                                    requestId))
                            .orElse(detail));
        }
    }

    private void showFailure() {
        binding.quoteEditError.setText(R.string.quote_failure);
    }

    private void setEnabled(boolean enabled) {
        binding.quoteTitle.setEnabled(enabled);
        binding.quoteNotes.setEnabled(enabled);
        binding.quoteProduct.setEnabled(enabled);
        binding.quoteTintProduct.setEnabled(enabled && pricingState.ready());
        binding.quotePriceList.setEnabled(enabled && pricingState.selectable());
        binding.quoteSave.setEnabled(enabled);
        binding.quoteCustomer.setEnabled(enabled && quoteId == 0);
    }

    private void restoreRetained() {
        Object retained = getLastCustomNonConfigurationInstance();
        if (!(retained instanceof RetainedEditor editor)) {
            return;
        }
        selectedCustomer = Optional.ofNullable(editor.customer());
        customers.addAll(editor.customers());
        products.addAll(editor.products());
        lines.addAll(editor.lines());
        binding.quoteTitle.setText(editor.title());
        binding.quoteNotes.setText(editor.notes());
        revision = editor.revision();
        pricingState = editor.pricingState();
        mutationAttempt = MaterialQuoteMutationAttempt.restored(
                editor.idempotencyKey(),
                editor.fingerprint());
        initialized = true;
        render();
    }

    private Optional<MaterialQuoteFeatureRuntime> runtime() {
        if (getApplication() instanceof MaterialQuoteRuntimeProvider provider) {
            return provider.materialQuoteRuntime();
        }
        return Optional.empty();
    }

    private static boolean isTransient(MaterialQuoteFailureKind kind) {
        return switch (kind) {
            case IDEMPOTENCY_IN_PROGRESS, NETWORK, RATE_LIMITED,
                    SERVICE_UNAVAILABLE -> true;
            default -> false;
        };
    }

    private static String text(EditText field) {
        return field.getText() == null ? "" : field.getText().toString();
    }

    private final class TintListener
            implements MaterialQuoteTintConfigurator.Listener {
        @Override
        public void onBusyChanged(boolean busy) {
            lookupInProgress = busy;
            binding.quoteEditProgress.setVisibility(
                    busy ? View.VISIBLE : View.INVISIBLE);
            setEnabled(!busy);
        }

        @Override
        public void onSelected(MaterialQuoteTintColor color) {
            binding.quoteEditError.setText("");
            askTintQuantity(color);
        }

        @Override
        public void onFailure(MaterialQuoteFailureKind kind) {
            if (kind == MaterialQuoteFailureKind.PRICE_POLICY_CHANGED) {
                binding.quoteEditError.setText(R.string.quote_price_policy_changed);
            } else if (kind == MaterialQuoteFailureKind.PRICE_NOT_AVAILABLE
                    || kind == MaterialQuoteFailureKind.TINT_MAPPING_PENDING) {
                binding.quoteEditError.setText(R.string.quote_tint_empty);
            } else {
                binding.quoteEditError.setText(R.string.quote_failure);
            }
        }
    }

    private static final class CatalogLookupFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        CatalogLookupFailure(CatalogException cause) {
            super(cause);
        }
    }

    private record RetainedEditor(
            CustomerSummary customer,
            List<CustomerSummary> customers,
            List<CatalogProduct> products,
            List<MaterialQuoteDraftLine> lines,
            String title,
            String notes,
            int revision,
            MaterialQuotePricingState pricingState,
            String idempotencyKey,
            String fingerprint) {
    }
}
