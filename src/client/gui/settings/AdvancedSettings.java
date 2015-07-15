package client.gui.settings;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.GroupLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JSpinner.DefaultEditor;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import javax.swing.border.TitledBorder;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;

import client.ClientExecutor;
import client.gui.JBytesBox;
import client.gui.MainFrame;
import client.gui.MainFrame.StatusHint;
import client.indexnode.internal.InternalIndexnodeManager;
import client.platform.ClientConfigDefaults.CK;
import client.platform.Relauncher;

import common.FS2Constants;
import common.Logger;
import common.Util;

@SuppressWarnings("serial")
public class AdvancedSettings extends SettingsPanel {
	
	InternalIndexnodeManager iim = frame.getGui().getShareServer().getIndexNodeCommunicator().getInternalIndexNode();
	Timer infoTimer;
	
	public AdvancedSettings(MainFrame frame) {
		super(frame, "Advanced", frame.getGui().getUtil().getImage("advanced"));
		
		JPanel boxes = createScrollableBoxlayout();
		
		// ###### Actual items go here:
		JLabel warning = new JLabel("<html>\"<b>You're probably going to break anything you change here</b>\"<br><i>--Captain Obvious</i></html>", frame.getGui().getUtil().getImage("failure"), SwingConstants.LEFT);
		warning.setAlignmentX(CENTER_ALIGNMENT);
		boxes.add(warning);
		boxes.add(createSlotsPanel());
		boxes.add(autoupdatePanel());
		boxes.add(heapSizePanel());
		boxes.add(autoindexnodePanel());
		boxes.add(portPanel());
		boxes.add(resetToDefaultsPanel());
		// ###### No more items.
		
		infoTimer = new Timer(FS2Constants.INTERNAL_INDEXNODE_RECONSIDER_INTERVAL_MS, new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				setHeapInfo();
				setPortNumberInfo();
				updateAutoIndexnodeInfo();
			}
		});
		infoTimer.start();
	}
	
	JLabel autoindexInfo = new JLabel();
	
	private JPanel autoindexnodePanel() {
		JPanel indexnodePanel = new JPanel(new BorderLayout());
		indexnodePanel.setBorder(getTitledBoldBorder("Internal indexnode"));
		
		updateAutoIndexnodeInfo();
		
		indexnodePanel.add(autoindexInfo, BorderLayout.NORTH);
		
		final JCheckBox autoindex = new JCheckBox("become an indexnode if needed", iim.isAutoIndexnodeEnabled());
		autoindex.setEnabled(false);
		autoindex.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				iim.setAutoIndexnode(autoindex.isSelected());
				updateAutoIndexnodeInfo();
			}
		});
		
		final JCheckBox always = new JCheckBox("always run an indexnode", iim.isAlwaysOn());
		always.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				iim.setAlwaysOn(always.isSelected());
				updateAutoIndexnodeInfo();
			}
		});
		
		indexnodePanel.add(autoindex, BorderLayout.WEST);
		indexnodePanel.add(always, BorderLayout.EAST);
		
		return indexnodePanel;
	}
	
	private void updateAutoIndexnodeInfo() {
		String status;
		
		if (iim.isCurrentlyActive()) {
			status = "active";
		} else if (iim.isAutoIndexNodeInhibitedAWTSAFE()) {
			status = "inhibited";
		} else if (iim.isAutoIndexnodeEnabled()) {
			status = "inactive";
		} else {
			status = "disabled";
		}
		
		String positionInfo = (iim.getRank() != 0 && (status.equals("inactive") || status.equals("active")) ? "<br>Our automatic indexnode rank is <b>" + iim.getRank() + "</b> out of <b>" + iim.getAlternativeNodes() + "</b>." : "");
		
		autoindexInfo.setText("<html>The internal indexnode is: <b>" + status + "</b>" + positionInfo + "</html>");
	}
	
	private JPanel createSlotsPanel() {
		JPanel slotsPanel = new JPanel(new FlowLayout(FlowLayout.LEADING));
		slotsPanel.setBorder(getTitledBoldBorder("Transfer slots"));
		
		JPanel innerPanel = new JPanel();
		GroupLayout layout = new GroupLayout(innerPanel);
		layout.setAutoCreateGaps(true);
		innerPanel.setLayout(layout);
		
		JLabel upLabel = new JLabel("<html><b>Upload:</b></html>");
		
		final JSpinner upMaxSlots = new JSpinner(new SpinnerNumberModel(frame.getGui().getShareServer().getUploadSlots(), 1, Integer.MAX_VALUE, 1));
		upMaxSlots.addChangeListener(new ChangeListener() {
			@Override
			public void stateChanged(ChangeEvent e) {
				frame.getGui().getShareServer().setUploadSlots((Integer) upMaxSlots.getValue());
			}
		});
		registerHint(upMaxSlots, new StatusHint(frame.getGui().getUtil().getImage("upload"), "The maximum number of concurrent uploads"));
		((DefaultEditor) upMaxSlots.getEditor()).getTextField().setColumns(3);
		
		JLabel upPerClientLabel = new JLabel("per user:");
		
		final JSpinner upMaxSlotsPerClient = new JSpinner(new SpinnerNumberModel(frame.getGui().getShareServer().getUploadSlotsPerUser(), 1, Integer.MAX_VALUE, 1));
		upMaxSlotsPerClient.addChangeListener(new ChangeListener() {
			@Override
			public void stateChanged(ChangeEvent e) {
				frame.getGui().getShareServer().setUploadSlotsPerUser((Integer) upMaxSlotsPerClient.getValue());
			}
		});
		registerHint(upMaxSlotsPerClient, new StatusHint(frame.getGui().getUtil().getImage("upload"), "The number of slots a single peer can use at once"));
		((DefaultEditor) upMaxSlotsPerClient.getEditor()).getTextField().setColumns(3);
		
		JLabel downLabel = new JLabel("<html><b>Download:</b></html>");
		
		final JSpinner downMaxSlots = new JSpinner(new SpinnerNumberModel(frame.getGui().getDc().getDownloadSlots(), 1, Integer.MAX_VALUE, 1));
		downMaxSlots.addChangeListener(new ChangeListener() {
			@Override
			public void stateChanged(ChangeEvent e) {
				frame.getGui().getDc().setDownloadSlots((Integer) downMaxSlots.getValue());
			}
		});
		registerHint(downMaxSlots, new StatusHint(frame.getGui().getUtil().getImage("download"), "The maximum number of concurrent downloads"));
		((DefaultEditor) downMaxSlots.getEditor()).getTextField().setColumns(3);
		
		JLabel downPartsLabel = new JLabel("per file:");
		
		final JSpinner downMaxParts = new JSpinner(new SpinnerNumberModel(frame.getGui().getDc().getMaxSlotsPerFile(), 1, Integer.MAX_VALUE, 1));
		downMaxParts.addChangeListener(new ChangeListener() {
			@Override
			public void stateChanged(ChangeEvent e) {
				frame.getGui().getDc().setMaxSlotsPerFile((Integer) downMaxParts.getValue());
			}
		});
		registerHint(downMaxParts, new StatusHint(frame.getGui().getUtil().getImage("download"), "The maximum number of chunks a single file can be split into for downloading."));
		((DefaultEditor) downMaxParts.getEditor()).getTextField().setColumns(3);
		
		GroupLayout.SequentialGroup hGroup = layout.createSequentialGroup();
		hGroup.addGroup(layout.createParallelGroup().addComponent(upLabel).addComponent(downLabel));
		hGroup.addGroup(layout.createParallelGroup().addComponent(upMaxSlots).addComponent(downMaxSlots));
		hGroup.addGroup(layout.createParallelGroup().addComponent(upPerClientLabel).addComponent(downPartsLabel));
		hGroup.addGroup(layout.createParallelGroup().addComponent(upMaxSlotsPerClient).addComponent(downMaxParts));
		layout.setHorizontalGroup(hGroup);
		
		GroupLayout.SequentialGroup vGroup = layout.createSequentialGroup();
		vGroup.addGroup(layout.createParallelGroup(GroupLayout.Alignment.BASELINE).addComponent(upLabel).addComponent(upMaxSlots).addComponent(upPerClientLabel).addComponent(upMaxSlotsPerClient));
		vGroup.addGroup(layout.createParallelGroup(GroupLayout.Alignment.BASELINE).addComponent(downLabel).addComponent(downMaxSlots).addComponent(downPartsLabel).addComponent(downMaxParts));
		layout.setVerticalGroup(vGroup);
		
		slotsPanel.add(innerPanel);
		
		return slotsPanel;
	}
	
	JLabel heapInfo = new JLabel();
	
	private JPanel heapSizePanel() {
		JPanel heapSizePanel = new JPanel();
		heapSizePanel.setLayout(new BorderLayout());
		heapSizePanel.setBorder(getTitledBoldBorder("Maximum heap size"));
		
		heapInfo.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 5));
		setHeapInfo();
		heapSizePanel.add(heapInfo, BorderLayout.WEST);
		
		final JBytesBox heapsize = new JBytesBox(frame.getGui().getConf().getLong(CK.HEAPSIZE));
		heapsize.addPropertyChangeListener("value", new PropertyChangeListener() {
			@Override
			public void propertyChange(PropertyChangeEvent evt) {
				long nv = (Long) evt.getNewValue();
				if (nv < 0) {
					frame.setStatusHint(new StatusHint(SettingsTab.ERROR, "FS2's heap size can't be set to '" + heapsize.getText() + "'."));
					return;
				}
				if (nv < 32 << 20) {
					frame.setStatusHint(new StatusHint(SettingsTab.ERROR, "The heap must be at least 32MiB."));
					return;
				}
				frame.getGui().getConf().putLong(CK.HEAPSIZE, nv);
				frame.setStatusHint(new StatusHint(SettingsTab.TICK, "FS2's heap set to " + Util.niceSize(nv) + ", click 'Restart FS2' to apply changes"));
				// Suppress the "I've changed your heap for you" message.
				frame.getGui().getConf().putBoolean(CK.AUTO_HEAP_KNOWLEDGE, true);
				restartNeeded();
			}
		});
		registerHint(heapsize, new StatusHint(frame.getGui().getUtil().getImage("heapsize"), "Set this to several GiB to host a large indexnode."));
		heapSizePanel.add(heapsize, BorderLayout.CENTER);
		
		JButton gc = new JButton(frame.getGui().getUtil().getImage("gc"));
		gc.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				System.gc();
				setHeapInfo();
			}
		});
		registerHint(gc, new StatusHint(frame.getGui().getUtil().getImage("gc"), "Triggers a garbage collection now."));
		heapSizePanel.add(gc, BorderLayout.EAST);
		
		return heapSizePanel;
	}
	
	private void setHeapInfo() {
		Runtime rt = Runtime.getRuntime();
		heapInfo.setText("<html>" +
			"Active JVM maximum heap size: <b>" + Util.niceSize(rt.maxMemory()) + "</b><br>" +
			"Current heap size: <b>" + Util.niceSize(rt.totalMemory()) + "</b><br>" +
			"Current heap usage: <b>" + Util.niceSize(rt.totalMemory() - rt.freeMemory()) + "</b><br>" +
			"Configured maximum heap size: " + "</html>"
		);
	}
	
	JLabel portNumberInfo = new JLabel();
	
	private JPanel portPanel() {
		JPanel portPanel = new JPanel(new BorderLayout());
		portPanel.setBorder(getTitledBoldBorder("Client port"));
		
		final JSpinner port = new JSpinner(new SpinnerNumberModel(frame.getGui().getConf().getInt(CK.PORT), 1, Integer.MAX_VALUE, 1));
		port.addChangeListener(new ChangeListener() {
			@Override
			public void stateChanged(ChangeEvent e) {
				frame.getGui().getConf().putInt(CK.PORT, ((Integer) port.getValue()));
				setPortNumberInfo();
				restartNeeded();
			}
		});
		registerHint(port, new StatusHint(frame.getGui().getUtil().getImage("network"), "The port that FS2 listens on. (port+1 is also used!)"));
		((DefaultEditor) port.getEditor()).getTextField().setColumns(7);
		
		JLabel portLabel = new JLabel("Current port:");
		
		JPanel portShrinker = new JPanel(new FlowLayout(FlowLayout.LEADING));
		portShrinker.add(portLabel);
		portShrinker.add(port);
		
		portPanel.add(portNumberInfo, BorderLayout.NORTH);
		portPanel.add(portShrinker, BorderLayout.CENTER);
		setPortNumberInfo();
		
		return portPanel;
	}
	
	private void setPortNumberInfo() {
		List<Integer> ports = new ArrayList<Integer>();
		ports.add(frame.getGui().getConf().getInt(CK.PORT));
		ports.add(frame.getGui().getConf().getInt(CK.PORT) + 1);
		ports.add(FS2Constants.ADVERTISEMENT_DATAGRAM_PORT);
		ports.add(FS2Constants.ADVERTISEMENT_DATAGRAM_PORT + 1);
		if (iim.isCurrentlyActive()) {
			ports.add(iim.getPort());
			ports.add(iim.getPort() + 1);
		}
		portNumberInfo.setText("<html>FS2 is currently using ports: <b>" + Util.join(ports, ", ") + "</b><br>Open these ports on your firewall to use FS2.</html>");
	}
	
	/**
	 * Configures autoupdate settings, triggers a check for updates right now.
	 * @return
	 */
	private JPanel autoupdatePanel() {
		JPanel updatePanel = new JPanel(new BorderLayout());
		updatePanel.setBorder(getTitledBoldBorder("Autoupdate"));
		
		String[] options = {"Automatically update (Recommended)", "Ask when updates are available", "Never update"};
		
		final JComboBox<String> choice = new JComboBox<String>(options);
		
		if (frame.getGui().getConf().getString(CK.UPDATE_POLICY).equals("none")) {
			choice.setSelectedIndex(2);
		} else if (frame.getGui().getConf().getString(CK.UPDATE_POLICY).equals("ask")) {
			choice.setSelectedIndex(1);
		} else if (frame.getGui().getConf().getString(CK.UPDATE_POLICY).equals("auto")) { // Pointless test for completeness.
			choice.setSelectedIndex(0);
		}
		
		choice.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				switch (choice.getSelectedIndex()) {
				case 0:
					frame.getGui().getConf().putString(CK.UPDATE_POLICY, "auto");
					break;
				case 1:
					frame.getGui().getConf().putString(CK.UPDATE_POLICY, "ask");
					break;
				default:
					frame.getGui().getConf().putString(CK.UPDATE_POLICY, "none");
					break;
				}
			}
		});
		
		JLabel auinfo = new JLabel("<html>Select <i>'" + options[2] + "'</i> to prevent FS2 from even checking for updates.</html>");
		updatePanel.add(auinfo, BorderLayout.NORTH);
		updatePanel.add(choice, BorderLayout.WEST);
		
		JButton aunow = new JButton("Check for updates now", frame.getGui().getUtil().getImage("checkupdates"));
		updatePanel.add(aunow, BorderLayout.EAST);
		aunow.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				ClientExecutor.getAcquire().checkForUpdatesNowAndAsk();
			}
		});
		
		return updatePanel;
	}
	
	private void restartNeeded() {
		restartFS2.setText(restartFS2.getText().toUpperCase());
		restartFS2.setBackground(JBytesBox.BAD);
		resetPanel.setBackground(JBytesBox.BAD);
		restartFS2.setFont(restartFS2.getFont().deriveFont(Font.BOLD | Font.ITALIC));
		((TitledBorder) resetPanel.getBorder()).setTitle("You need to restart FS2 to apply the changes!");
	}
	
	private JButton resetFS2;
	private JButton restartFS2; // Clicking this restarts the FS2 client.
	private JPanel resetPanel;
	
	/**
	 * A single button that nukes FS2's configuration, and a button to relaunch FS2.
	 * @return
	 */
	private JPanel resetToDefaultsPanel() {
		resetPanel = new JPanel(new FlowLayout(FlowLayout.LEADING));
		resetPanel.setBorder(getTitledBoldBorder("Reset controls"));
		
		restartFS2 = new JButton("Restart FS2", frame.getGui().getUtil().getImage("refresh"));
		restartFS2.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				// This is done by restarting to a new heap of possibly the same size.
				if (!Relauncher.increaseHeap(frame.getGui().getConf().getLong(CK.HEAPSIZE), false)) {
					JOptionPane.showMessageDialog(null, "The client couldn't be restarted. Restart is only supported from .jar files.", "Restart failure.", JOptionPane.ERROR_MESSAGE);
				}
			}
		});
		registerHint(restartFS2, new StatusHint(frame.getGui().getUtil().getImage("refresh"), "This restarts FS2. Use it to apply some settings or to fix weird behaviour."));
		
		resetFS2 = new JButton("Reset configuration to defaults", frame.getGui().getUtil().getImage("failure"));
		resetFS2.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				if (JOptionPane.showOptionDialog(frame, new JLabel("<html>If you continue FS2 will shutdown and erase its configuration.<br><b>You will have to manually restart FS2.</b></html>", frame.getGui().getUtil().getImage("failure"), SwingConstants.LEFT), "Really?", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE, null, new String[] {"Erase configuration", "cancel"}, "Erase configuration") == JOptionPane.YES_OPTION) {
					Logger.severe("Erasing FS2's configuration...");
					frame.getGui().getConf().eraseOnShutdown();
					frame.getGui().triggerShutdown();
				}
			}
		});
		registerHint(resetFS2, new StatusHint(frame.getGui().getUtil().getImage("failure"), "This resets all changes to FS2's default configuration. USE WITH CARE."));
		
		resetPanel.add(restartFS2);
		resetPanel.add(resetFS2);
		
		return resetPanel;
	}
	
}
