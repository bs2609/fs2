package client.gui.settings;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.image.BufferedImage;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.GroupLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.filechooser.FileNameExtensionFilter;

import client.gui.JBytesBox;
import client.gui.JTextFieldLimit;
import client.gui.MainFrame;
import client.gui.MainFrame.StatusHint;
import client.gui.Utilities;
import client.platform.Platform;

import common.FS2Constants;
import common.Logger;
import common.Util;

@SuppressWarnings("serial")
public class BasicSettings extends SettingsPanel implements KeyListener {
	
	public BasicSettings(MainFrame frame) {
		super(frame, "Basic", frame.getGui().getUtil().getImage("basic"));
		
		// Construct a basic settings page including: alias, avatar, etc.
		JPanel boxes = createScrollableBoxlayout();
		boxes.add(createAliasPanel());
		boxes.add(createDDPanel());
		boxes.add(createSpeedsPanel());
	}
	
	private JPanel createSpeedsPanel() {
		JPanel speedsPanel = new JPanel();
		
		GroupLayout layout = new GroupLayout(speedsPanel);
		layout.setAutoCreateGaps(true);
		
		speedsPanel.setLayout(layout);
		speedsPanel.setBorder(getTitledBoldBorder("Maximum transfer speeds"));
		
		JLabel upLabel = new JLabel("Upload:");
		final JBytesBox upSpeed = new JBytesBox(frame.getGui().getShareServer().getUploadSpeed());
		upSpeed.addPropertyChangeListener("value", new PropertyChangeListener() {
			@Override
			public void propertyChange(PropertyChangeEvent evt) {
				long nv = (Long) evt.getNewValue();
				if (nv < 0) {
					frame.setStatusHint(new StatusHint(SettingsTab.ERROR, "The upload speed can't be set to '" + upSpeed.getText() + "'."));
				} else {
					frame.getGui().getShareServer().setUploadSpeed(nv);
					frame.setStatusHint(new StatusHint(SettingsTab.TICK, "The upload speed has been set to " + Util.niceSize(nv)));
				}
			}
		});
		registerHint(upSpeed, new StatusHint(SettingsTab.TICK, "(saved on change) The maximum upload amount per second, examples: 5.5mb, 10b, 999tib"));
		
		JLabel downLabel = new JLabel("Download:");
		final JBytesBox downSpeed = new JBytesBox(frame.getGui().getDc().getDownloadSpeed());
		downSpeed.addPropertyChangeListener("value", new PropertyChangeListener() {
			@Override
			public void propertyChange(PropertyChangeEvent evt) {
				long nv = (Long) evt.getNewValue();
				if (nv < 0) {
					frame.setStatusHint(new StatusHint(SettingsTab.ERROR, "The download speed can't be set to '" + downSpeed.getText() + "'."));
				} else {
					frame.getGui().getDc().setDownloadSpeed(nv);
					frame.setStatusHint(new StatusHint(SettingsTab.TICK, "The download speed has been set to " + Util.niceSize(nv)));
				}
			}
		});
		registerHint(downSpeed, new StatusHint(SettingsTab.TICK, "(saved on change) The maximum download amount per second, examples: 5.5mb, 10b, 999tib"));
		
		GroupLayout.SequentialGroup hGroup = layout.createSequentialGroup();
		hGroup.addGroup(layout.createParallelGroup().addComponent(upLabel).addComponent(downLabel));
		hGroup.addGroup(layout.createParallelGroup().addComponent(upSpeed).addComponent(downSpeed));
		layout.setHorizontalGroup(hGroup);
		
		GroupLayout.SequentialGroup vGroup = layout.createSequentialGroup();
		vGroup.addGroup(layout.createParallelGroup(GroupLayout.Alignment.BASELINE).addComponent(upLabel).addComponent(upSpeed));
		vGroup.addGroup(layout.createParallelGroup(GroupLayout.Alignment.BASELINE).addComponent(downLabel).addComponent(downSpeed));
		layout.setVerticalGroup(vGroup);
		
		return speedsPanel;
	}
	
	private JPanel createDDPanel() {
		final JLabel currentLocation = new JLabel(frame.getGui().getDc().getDefaultDownloadDirectory().getPath());
		final JButton downloadDirectory = new JButton("<html><b>Browse</b></html>", frame.getGui().getUtil().getImage("type-dir"));
		downloadDirectory.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				JFileChooser fc = new JFileChooser(frame.getGui().getDc().getDefaultDownloadDirectory());
				fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
				int retVal = fc.showOpenDialog(null);
				if (retVal == JFileChooser.APPROVE_OPTION) {
					frame.getGui().getDc().setDefaultDownloadDirectory(fc.getSelectedFile());
					frame.getGui().getShareServer().defaultDownloadDirectoryChanged(fc.getSelectedFile()); // Change the "My Downloads" share if it still exists.
					currentLocation.setText(frame.getGui().getDc().getDefaultDownloadDirectory().getPath());
				}
			}
		});
		
		JPanel ddPanel = new JPanel(new BorderLayout());
		ddPanel.setBorder(getTitledBoldBorder("Default download directory"));
		JPanel panel0 = new JPanel();
		ddPanel.add(panel0, BorderLayout.WEST);
		ddPanel.add(currentLocation, BorderLayout.NORTH);
		currentLocation.setAlignmentX(LEFT_ALIGNMENT);
		panel0.add(downloadDirectory);
		registerHint(downloadDirectory, new StatusHint(frame.getGui().getUtil().getImage("type-dir"), "This is where your downloads will go to by default"));
		
		return ddPanel;
	}
	
	JButton avatarButton;
	private JTextField aliasText;
	
	private JPanel createAliasPanel() {
		JPanel aliasPanel = new JPanel(new BorderLayout());
		aliasPanel.setBorder(getTitledBoldBorder("Alias and Avatar"));
		
		JPanel panel0 = new JPanel(new BorderLayout());
		aliasPanel.add(panel0, BorderLayout.NORTH);
		
		aliasText = new JTextField();
		JPanel panel1 = new JPanel();
		panel1.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
		panel1.setLayout(new BoxLayout(panel1, BoxLayout.PAGE_AXIS));
		panel1.add(new Box.Filler(new Dimension(2, 2), new Dimension(2, 2), new Dimension(2, 200))); // Enable the slack space to be taken in.s
		panel1.add(aliasText);
		aliasText.setMaximumSize(new Dimension(aliasText.getMaximumSize().width, aliasText.getMinimumSize().height)); // Fix to be the correct height.
		panel1.add(new Box.Filler(new Dimension(2, 2), new Dimension(2, 2), new Dimension(2, 200)));
		panel0.add(panel1, BorderLayout.CENTER);
		
		aliasText.setDocument(new JTextFieldLimit(32));
		aliasText.setText(frame.getGui().getShareServer().getAlias());
		aliasText.addKeyListener(this);
		registerHint(aliasText, new StatusHint(frame.getGui().getUtil().getImage("tick"), "(saved on change) Set your alias on the FS2 network here."));
		
		ImageIcon icon = frame.getGui().getUtil().getImage("defaultavatar");
		File avatarFile = frame.getGui().getShareServer().getIndexNodeCommunicator().getAvatarFile();
		if (avatarFile.isFile()) {
			try {
				icon = new ImageIcon(ImageIO.read(avatarFile));
			} catch (IOException e) {
				Logger.warn("Avatar " + avatarFile.getPath() + " couldn't be loaded from disk: " + e);
			}
		}
		
		avatarButton = new JButton(icon);
		avatarButton.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				setIcon();
			}
		});
		registerHint(avatarButton, new StatusHint(frame.getGui().getUtil().getImage("type-image"), "Click this button to set your avatar"));
		panel0.add(avatarButton, BorderLayout.WEST);
		
		return aliasPanel;
	}
	
	private File lastUsedIconPath;
	
	private void setIcon() {
		JFileChooser iconPicker = new JFileChooser(lastUsedIconPath);
		FileNameExtensionFilter filter = new FileNameExtensionFilter("Images", "jpg", "gif", "jpeg", "png", "tiff", "bmp");
		iconPicker.setFileFilter(filter);
		int result = iconPicker.showOpenDialog(this);
		if (result != JFileChooser.APPROVE_OPTION) return;
		try {
			lastUsedIconPath = iconPicker.getCurrentDirectory();
			try (InputStream fis = new BufferedInputStream(new FileInputStream(iconPicker.getSelectedFile()))) {
				
				final BufferedImage chosen = Util.processImageInternal(fis, FS2Constants.FS2_AVATAR_ICON_SIZE, FS2Constants.FS2_AVATAR_ICON_SIZE, Util.ImageResizeType.OUTER); //resize to appropriate dimensions.
				avatarButton.setText("Sending...");
				avatarButton.setEnabled(false);
				avatarButton.setIcon(new ImageIcon(chosen));
				
				Thread worker = new Thread(new Runnable() {
					@Override
					public void run() {
						boolean success;
						IOException ex = null;
						try {
							// 1) Save the resized image to a cache file:
							File avatarCache = Platform.getPlatformFile("avatar.png");
							ImageIO.write(chosen, "png", avatarCache);
							// 2) Set the indexnode communicator to use this file:
							frame.getGui().getShareServer().getIndexNodeCommunicator().setAvatarFile(avatarCache);
							success = true;
							
						} catch (IOException e) {
							ex = e;
							success = false;
							Logger.warn("Couldn't send avatar to indexnode: " + e);
						}
						
						final boolean esuccess = success;
						final IOException eex = ex;
						
						Utilities.edispatch(new Runnable() {
							@Override
							public void run() {
								avatarButton.setText(esuccess ? "" : "failure: " + eex);
								avatarButton.setEnabled(true);
							}
						});
					}
				});
				worker.setName("avatar change submitter");
				worker.start();
			}
			
		} catch (Exception ex) {
			Logger.warn("Couldn't load a selected avatar: " + ex);
			avatarButton.setText(iconPicker.getSelectedFile().getName() + " can't be loaded.");
		}
	}
	
	@Override
	public void keyReleased(KeyEvent e) {
		if (e.getSource() == aliasText) frame.getGui().getShareServer().setAlias(aliasText.getText());
	}
	
	@Override
	public void keyPressed(KeyEvent e) {}
	
	@Override
	public void keyTyped(KeyEvent e) {}
	
}
