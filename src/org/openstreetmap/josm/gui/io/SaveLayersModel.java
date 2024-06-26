// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.gui.io;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import javax.swing.table.DefaultTableModel;

import org.openstreetmap.josm.gui.layer.AbstractModifiableLayer;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;

/**
 * Table model for the {@link SaveLayersTable} in the {@link SaveLayersDialog}.
 */
public class SaveLayersModel extends DefaultTableModel {
    public static final String MODE_PROP = SaveLayerInfo.class.getName() + ".mode";

    /**
     * The status of the editor
     */
    public enum Mode {
        EDITING_DATA,
        UPLOADING_AND_SAVING
    }

    private transient List<SaveLayerInfo> layerInfo;
    private Mode mode;
    private final PropertyChangeSupport support;

    // keep in sync with how the columns are ordered in SaveLayersTableColumnModel#build
    private static final int columnFilename = 0;
    private static final int columnActions = 2;

    /**
     * Constructs a new {@code SaveLayersModel}.
     */
    public SaveLayersModel() {
        mode = Mode.EDITING_DATA;
        support = new PropertyChangeSupport(this);
    }

    public void addPropertyChangeListener(PropertyChangeListener l) {
        support.addPropertyChangeListener(l);
    }

    public void removePropertyChangeListener(PropertyChangeListener l) {
        support.removePropertyChangeListener(l);
    }

    protected void fireModeChanged(Mode oldValue, Mode newValue) {
        support.firePropertyChange(MODE_PROP, oldValue, newValue);
    }

    public void setMode(Mode newValue) {
        Mode oldValue = this.mode;
        this.mode = newValue;
        fireModeChanged(oldValue, newValue);
    }

    public Mode getMode() {
        return mode;
    }

    /**
     * Populates the model with given modifiable layers.
     * @param layers The layers to use to populate this model
     * @since 7358
     */
    public void populate(List<? extends AbstractModifiableLayer> layers) {
        layerInfo = new ArrayList<>();
        if (layers == null) return;
        for (AbstractModifiableLayer layer: layers) {
            layerInfo.add(new SaveLayerInfo(layer));
        }
        layerInfo.sort(Comparator.naturalOrder());
        fireTableDataChanged();
    }

    @Override
    public int getRowCount() {
        if (layerInfo == null) return 0;
        return layerInfo.size();
    }

    @Override
    public Object getValueAt(int row, int column) {
        if (layerInfo == null) return null;
        return layerInfo.get(row);
    }

    @Override
    public boolean isCellEditable(int row, int column) {
        return column == columnFilename || column == columnActions;
    }

    @Override
    public void setValueAt(Object value, int row, int column) {
        final SaveLayerInfo info = this.layerInfo.get(row);
        switch (column) {
        case columnFilename:
            info.setFile((File) value);
            if (info.isSavable()) {
                info.setDoSaveToFile(true);
            }
            break;
        case columnActions:
            boolean[] values = (boolean[]) value;
            info.setDoUploadToServer(values[0]);
            info.setDoSaveToFile(values[1]);
            break;
        default: // Do nothing
        }
        fireTableDataChanged();
    }

    public List<SaveLayerInfo> getSafeLayerInfo() {
        return this.layerInfo;
    }

    public List<SaveLayerInfo> getLayersWithoutFilesAndSaveRequest() {
        if (layerInfo == null) {
            return Collections.emptyList();
        }
        return layerInfo.stream().filter(info -> info.isDoSaveToFile() && info.getFile() == null).collect(Collectors.toList());
    }

    public List<SaveLayerInfo> getLayersWithIllegalFilesAndSaveRequest() {
        if (layerInfo == null) {
            return Collections.emptyList();
        }
        return layerInfo.stream()
                .filter(info -> info.isDoSaveToFile() && info.getFile() != null && info.getFile().exists() && !info.getFile().canWrite())
                .collect(Collectors.toList());
    }

    public List<SaveLayerInfo> getLayersWithConflictsAndUploadRequest() {
        List<SaveLayerInfo> ret = new ArrayList<>();
        if (layerInfo != null) {
            for (SaveLayerInfo info: layerInfo) {
                AbstractModifiableLayer l = info.getLayer();
                if (info.isDoUploadToServer() && l instanceof OsmDataLayer && !((OsmDataLayer) l).getConflicts().isEmpty()) {
                    ret.add(info);
                }
            }
        }
        return ret;
    }

    public List<SaveLayerInfo> getLayersToUpload() {
        if (layerInfo == null) {
            return Collections.emptyList();
        }
        return layerInfo.stream().filter(SaveLayerInfo::isDoUploadToServer).collect(Collectors.toList());
    }

    public List<SaveLayerInfo> getLayersToSave() {
        if (layerInfo == null) {
            return Collections.emptyList();
        }
        return layerInfo.stream().filter(SaveLayerInfo::isDoSaveToFile).collect(Collectors.toList());
    }

    public void setUploadState(AbstractModifiableLayer layer, UploadOrSaveState state) {
        SaveLayerInfo info = getSaveLayerInfo(layer);
        if (info != null) {
            info.setUploadState(state);
        }
        fireTableDataChanged();
    }

    public void setSaveState(AbstractModifiableLayer layer, UploadOrSaveState state) {
        SaveLayerInfo info = getSaveLayerInfo(layer);
        if (info != null) {
            info.setSaveState(state);
        }
        fireTableDataChanged();
    }

    public SaveLayerInfo getSaveLayerInfo(AbstractModifiableLayer layer) {
        return this.layerInfo.stream()
                .filter(info -> info.getLayer() == layer)
                .findFirst().orElse(null);
    }

    public void resetSaveAndUploadState() {
        for (SaveLayerInfo info: layerInfo) {
            info.setSaveState(null);
            info.setUploadState(null);
        }
    }

    public boolean hasUnsavedData() {
        for (SaveLayerInfo info: layerInfo) {
            if (info.isDoUploadToServer() && UploadOrSaveState.OK != info.getUploadState())
                return true;
            if (info.isDoSaveToFile() && UploadOrSaveState.OK != info.getSaveState())
                return true;
        }
        return false;
    }

    public int getNumCancel() {
        return (int) layerInfo.stream()
                .filter(info -> UploadOrSaveState.CANCELED == info.getSaveState() || UploadOrSaveState.CANCELED == info.getUploadState())
                .count();
    }

    public int getNumFailed() {
        return (int) layerInfo.stream()
                .filter(info -> UploadOrSaveState.FAILED == info.getSaveState() || UploadOrSaveState.FAILED == info.getUploadState())
                .count();
    }
}
