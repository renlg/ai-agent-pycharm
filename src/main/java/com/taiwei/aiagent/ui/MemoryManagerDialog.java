package com.taiwei.aiagent.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.table.JBTable;
import com.taiwei.aiagent.memory.MemoryCategory;
import com.taiwei.aiagent.memory.MemoryEntry;
import com.taiwei.aiagent.memory.MemoryManager;
import com.taiwei.aiagent.util.I18nUtil;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Memory 管理面板：查看已存储的长期记忆、按关键词/分类搜索、以及删除。
 * 非模态弹窗，允许用户在弹窗打开的同时继续与聊天面板交互。
 */
public class MemoryManagerDialog extends DialogWrapper {

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
    private static final int LIST_LIMIT = 200;

    private final MemoryManager memoryManager;
    private MemoryTableModel tableModel;
    private JBTable table;
    private JBTextField searchField;
    private JComboBox<String> categoryFilter;

    public MemoryManagerDialog(@NotNull Project project) {
        super(project, false, IdeModalityType.MODELESS);
        this.memoryManager = MemoryManager.getInstance(project);
        setTitle(I18nUtil.getMessage("memory.manager.title"));
        setCancelButtonText(I18nUtil.getMessage("memory.manager.closeBtn"));
        setModal(false);
        init();
    }

    @Override
    protected JComponent createCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(buildSearchBar(), BorderLayout.NORTH);

        tableModel = new MemoryTableModel(loadMemories(null, null));
        table = new JBTable(tableModel);
        table.setRowHeight(28);
        table.getColumnModel().getColumn(0).setPreferredWidth(320);
        table.getColumnModel().getColumn(1).setPreferredWidth(90);
        table.getColumnModel().getColumn(2).setPreferredWidth(150);
        table.getColumnModel().getColumn(3).setPreferredWidth(60);
        table.getColumnModel().getColumn(4).setPreferredWidth(120);

        JBScrollPane scrollPane = new JBScrollPane(table);
        scrollPane.setPreferredSize(new Dimension(760, 360));
        panel.add(scrollPane, BorderLayout.CENTER);
        return panel;
    }

    private JComponent buildSearchBar() {
        JPanel bar = new JPanel(new BorderLayout(8, 0));
        bar.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));

        searchField = new JBTextField();
        searchField.addActionListener(e -> onSearch());

        categoryFilter = new JComboBox<>(buildCategoryOptions());
        categoryFilter.addActionListener(e -> onSearch());

        JButton searchButton = new JButton(I18nUtil.getMessage("memory.manager.searchBtn"));
        searchButton.addActionListener(e -> onSearch());

        JPanel right = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        right.add(categoryFilter);
        right.add(searchButton);

        bar.add(searchField, BorderLayout.CENTER);
        bar.add(right, BorderLayout.EAST);
        return bar;
    }

    private String[] buildCategoryOptions() {
        String[] options = new String[MemoryCategory.values().length + 1];
        options[0] = I18nUtil.getMessage("memory.manager.allCategories");
        for (int i = 0; i < MemoryCategory.values().length; i++) {
            options[i + 1] = MemoryCategory.values()[i].name();
        }
        return options;
    }

    private void onSearch() {
        String query = searchField.getText() == null ? "" : searchField.getText().trim();
        MemoryCategory category = selectedCategory();
        tableModel.setMemories(loadMemories(query.isEmpty() ? null : query, category));
    }

    private MemoryCategory selectedCategory() {
        int index = categoryFilter.getSelectedIndex();
        if (index <= 0) return null;
        return MemoryCategory.values()[index - 1];
    }

    private List<MemoryEntry> loadMemories(String query, MemoryCategory category) {
        if (query != null && !query.isEmpty()) {
            List<MemoryEntry> results = memoryManager.recall(query, LIST_LIMIT);
            if (category == null) return results;
            List<MemoryEntry> filtered = new ArrayList<>();
            for (MemoryEntry entry : results) {
                if (entry.getCategory() == category) filtered.add(entry);
            }
            return filtered;
        }
        return memoryManager.list(category, LIST_LIMIT);
    }

    @Override
    protected Action @NotNull [] createActions() {
        return new Action[]{getCancelAction()};
    }

    @Override
    protected @NotNull Action @NotNull [] createLeftSideActions() {
        return new Action[]{new RefreshAction(), new DeleteAction()};
    }

    private void onRefresh() {
        searchField.setText("");
        categoryFilter.setSelectedIndex(0);
        tableModel.setMemories(loadMemories(null, null));
    }

    private void onDeleteSelected() {
        int row = table.getSelectedRow();
        if (row < 0) return;
        MemoryEntry entry = tableModel.getMemoryAt(row);
        memoryManager.forget(entry.getId());
        onSearch();
    }

    // ========== 按钮 ==========

    private class RefreshAction extends AbstractAction {
        RefreshAction() {
            super(I18nUtil.getMessage("memory.manager.refreshBtn"));
        }

        @Override
        public void actionPerformed(java.awt.event.ActionEvent e) {
            onRefresh();
        }
    }

    private class DeleteAction extends AbstractAction {
        DeleteAction() {
            super(I18nUtil.getMessage("memory.manager.deleteBtn"));
        }

        @Override
        public void actionPerformed(java.awt.event.ActionEvent e) {
            onDeleteSelected();
        }
    }

    // ========== Table Model ==========

    private static class MemoryTableModel extends AbstractTableModel {
        private final String[] columns = {
                I18nUtil.getMessage("memory.manager.columnContent"),
                I18nUtil.getMessage("memory.manager.columnCategory"),
                I18nUtil.getMessage("memory.manager.columnTags"),
                I18nUtil.getMessage("memory.manager.columnImportance"),
                I18nUtil.getMessage("memory.manager.columnLastAccessed")
        };
        private List<MemoryEntry> memories;

        MemoryTableModel(List<MemoryEntry> memories) {
            this.memories = memories;
        }

        void setMemories(List<MemoryEntry> memories) {
            this.memories = memories;
            fireTableDataChanged();
        }

        MemoryEntry getMemoryAt(int row) {
            return memories.get(row);
        }

        @Override
        public int getRowCount() {
            return memories.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return false;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            MemoryEntry entry = memories.get(rowIndex);
            switch (columnIndex) {
                case 0:
                    return entry.getContent();
                case 1:
                    return entry.getCategory().name();
                case 2:
                    return String.join(", ", entry.getTags());
                case 3:
                    return entry.getImportance();
                case 4:
                    return DATE_FORMAT.format(Instant.ofEpochMilli(entry.getLastAccessedAt()));
                default:
                    return "";
            }
        }
    }
}
