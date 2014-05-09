package client.gui.settings;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.CharBuffer;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumn;

import client.gui.FancierTable;
import client.gui.MainFrame;
import client.gui.MainFrame.StatusHint;
import client.indexnode.IndexNode;
import client.indexnode.IndexNodeCommunicator;
import client.platform.ClientConfigDefaults.CK;

import common.FS2Constants;
import common.Logger;
import common.Util;

@SuppressWarnings("serial")
public class IndexnodeSettings extends SettingsPanel {
	
	private class IndexNodeStatusRenderer extends DefaultTableCellRenderer {
		@Override
		public Component getTableCellRendererComponent(JTable table,
			Object value, boolean isSelected, boolean hasFocus, int row, int column)
		{
			super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
			
			IndexNode node = comm.getRegisteredIndexNodes().get(nodesTable.convertRowIndexToModel(row));
			setIcon(node.wasAdvertised() ? frame.getGui().getUtil().getImage("autodetect") : null);
			
			return this;
		}
	}
	
	private class IndexNodeDateRenderer extends DefaultTableCellRenderer {
		@Override
		public Component getTableCellRendererComponent(JTable table,
			Object value, boolean isSelected, boolean hasFocus, int row, int column)
		{
			super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
			
			Date lastSeen = (Date) value;
			setText(lastSeen.getTime() == 0 ? "never" : Util.formatDate(lastSeen));
			
			return this;
		}
	}
	
	private class IndexNodeNameRenderer extends DefaultTableCellRenderer {
		@Override
		public Component getTableCellRendererComponent(JTable table,
			Object value, boolean isSelected, boolean hasFocus, int row, int column)
		{
			super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
			
			IndexNode node = comm.getRegisteredIndexNodes().get(nodesTable.convertRowIndexToModel(row));
			
			String icon;
			switch (node.getNodeStatus()) {
			case ACTIVE:
				icon = node.isSecure() ? "secure" : "connect";
				break;
			case AUTHREQUIRED:
				icon = "secure";
				break;
			case UNCONTACTABLE:
				icon = "disconnect";
				break;
			case INCOMPATIBLE:
				icon = "error";
				break;
			case FIREWALLED:
				icon = "failure";
				break;
			default:
				icon = "disconnect";
				break;
			}
			setIcon(frame.getGui().getUtil().getImage(icon));
			
			return this;
		}
	}
	
	private JTable nodesTable;
	private JCheckBox autodetect;
	private IndexNodeCommunicator comm;
	private JButton addIndexnode, removeIndexnode, setPassword;
	
	public IndexnodeSettings(final MainFrame frame) {
		super(frame, "Indexnodes", frame.getGui().getUtil().getImage("autodetect"));
		
		comm = frame.getGui().getShareServer().getIndexNodeCommunicator();
		
		setupAutoDetectButton(frame);
		setupButtons(frame);
		
		nodesTable = new FancierTable(comm, frame.getGui().getConf(), CK.INDEXNODE_TABLE_COLWIDTHS);
		add(new JScrollPane(nodesTable), BorderLayout.CENTER);
		for (int i = 0; i < comm.getColumnCount(); i++) {
			TableColumn col = nodesTable.getColumn(comm.getColumnName(i));
			if (i == 0) col.setCellRenderer(new IndexNodeNameRenderer());
			if (i == 1) col.setCellRenderer(new IndexNodeStatusRenderer());
			if (i == 2) col.setCellRenderer(new IndexNodeDateRenderer());
		}
		registerHint(nodesTable, new StatusHint(frame.getGui().getUtil().getImage("autodetect"), "Lists all indexnodes known to FS2 at this point in time"));
		
		nodesTable.getSelectionModel().addListSelectionListener(new ListSelectionListener() {
			@Override
			public void valueChanged(ListSelectionEvent e) {
				if (nodesTable.getSelectedRows().length == 0) {
					removeIndexnode.setEnabled(false);
					setPassword.setEnabled(false);
				} else {
					removeIndexnode.setEnabled(true);
					setPassword.setEnabled(comm.getRegisteredIndexNodes().get(nodesTable.convertRowIndexToModel(nodesTable.getSelectedRow())).isSecure());
				}
			}
		});
	}
	
	private void setupButtons(final MainFrame frame) {
		JPanel buttonsPanel = new JPanel(new FlowLayout());
		add(buttonsPanel, BorderLayout.SOUTH);
		
		addIndexnode = new JButton("Add indexnode...", frame.getGui().getUtil().getImage("add"));
		addIndexnode.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				addIndexnode(frame);
			}
		});
		registerHint(addIndexnode, new StatusHint(frame.getGui().getUtil().getImage("add"), "Click here to add a new indexnode manually"));
		
		removeIndexnode = new JButton("Remove selected indexnode", frame.getGui().getUtil().getImage("delete"));
		removeIndexnode.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				removeIndexnode();
			}
		});
		registerHint(removeIndexnode,new StatusHint(frame.getGui().getUtil().getImage("delete"), "Click here to de-register the selected indexnode"));
		removeIndexnode.setEnabled(false);
		
		setPassword = new JButton("Provide password...", frame.getGui().getUtil().getImage("unlock"));
		setPassword.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				setPassword();
			}
		});
		registerHint(setPassword, new StatusHint(frame.getGui().getUtil().getImage("unlock"), "Click here to provide a password for a secure indexnode..."));
		setPassword.setEnabled(false);
		
		buttonsPanel.add(addIndexnode);
		buttonsPanel.add(removeIndexnode);
		buttonsPanel.add(setPassword);
	}
	
	private void setupAutoDetectButton(final MainFrame frame) {
		JPanel autoPanel = new JPanel(new BorderLayout());
		add(autoPanel, BorderLayout.NORTH);
		
		autodetect = new JCheckBox();
		autodetect.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				if (autodetect.isSelected()) {
					comm.enableAdvertAcceptance();
				} else {
					comm.disableAdvertAcceptance();
				}
			}
		});
		autodetect.setSelected(comm.isListeningForAdverts());
		autoPanel.add(autodetect,BorderLayout.CENTER);
		registerHint(autodetect, new StatusHint(frame.getGui().getUtil().getImage("autodetect"), "(saved on change) Check this box to enable autodetection of indexnodes"));
		
		JLabel autolabel = new JLabel("Autodetect indexnodes: ", frame.getGui().getUtil().getImage("autodetect"), JLabel.LEFT);
		autoPanel.add(autolabel, BorderLayout.WEST);
		registerHint(autolabel, new StatusHint(frame.getGui().getUtil().getImage("autodetect"), "(saved on change) Check this box to enable autodetection of indexnodes"));
	}
	
	private void addIndexnode(final MainFrame frame) {
		String result = (String) JOptionPane.showInputDialog(null, "Enter the URL of the new indexnode:", "New Indexnode", JOptionPane.QUESTION_MESSAGE, null, null, "");
		if (result == null) return;
		try {
			if (!result.toLowerCase().startsWith("http://")) result = "http://" + result;
			final URL resURL = new URL(result);
			Thread elsewhere = new Thread(new Runnable() {
				@Override
				public void run() {
					comm.registerNewIndexNode(resURL);
				}
			}, "Add new indexnode thread");
			elsewhere.setDaemon(true);
			elsewhere.start();
			frame.setStatusHint("Added: " + result + "... It might take a few seconds to show up...");
			
		} catch (MalformedURLException ex) {
			frame.setStatusHint(new StatusHint(frame.getGui().getUtil().getImage("error"), "Invalid new indexnode URL! (" + ex.getMessage() + ")"));
			Logger.log("Invalid new indexnode URL: " + ex);
		}
	}
	
	private void removeIndexnode() {
		int[] togo = nodesTable.getSelectedRows();
		List<IndexNode> goodbye = new ArrayList<IndexNode>();
		for (int i : togo) {
			goodbye.add(comm.getRegisteredIndexNodes().get(nodesTable.convertRowIndexToModel(i)));
		}
		for (IndexNode n : goodbye) comm.deregisterIndexNode(n);
	}
	
	private void setPassword() {
		JPasswordField password = new JPasswordField();
		JLabel label = new JLabel("<html><b>Enter this indexnode's password carefully.</b><br>The indexnode may create you an account if you do not already have one.</html>");
		if (JOptionPane.showConfirmDialog(null, new Object[] {label, password}, "Password:", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
			IndexNode node = comm.getRegisteredIndexNodes().get(nodesTable.convertRowIndexToModel(nodesTable.getSelectedRow()));
			node.setPassword(Util.md5(FS2Constants.FS2_USER_PASSWORD_SALT + CharBuffer.wrap(password.getPassword())));
			for (int i = 0; i < password.getPassword().length; i++) password.getPassword()[i] = 0; // Null out password from memory.
		}
	}
	
}
