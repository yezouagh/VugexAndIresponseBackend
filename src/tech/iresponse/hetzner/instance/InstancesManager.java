package tech.iresponse.hetzner.instance;

import java.beans.ConstructorProperties;
import java.sql.Date;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Pattern;
import org.json.JSONObject;
import tech.iresponse.http.Agents;
import tech.iresponse.logging.Loggers;
import tech.iresponse.orm.Connector;
import tech.iresponse.models.admin.HetznerAccount;
import tech.iresponse.models.admin.ServerProvider;
import tech.iresponse.models.admin.HetznerInstance;
import tech.iresponse.models.admin.HetznerProcess;
import tech.iresponse.models.admin.Domain;
import tech.iresponse.models.admin.MtaServer;
import tech.iresponse.hetzner.HetznerManager;
import tech.iresponse.hetzner.update.UpdateData;
import tech.iresponse.exceptions.DatabaseException;
import tech.iresponse.orm.Database;
import tech.iresponse.utils.Strings;
import tech.iresponse.utils.ThreadSleep;
import tech.iresponse.utils.TypesParser;
import tech.iresponse.core.Application;
import tech.iresponse.helpers.scripts.CloudServices;
import tech.iresponse.helpers.scripts.InstallationServices;
import tech.iresponse.remote.Authentification;
import tech.iresponse.remote.SSHConnector;
import tech.iresponse.webservices.Hetzner;

public class InstancesManager extends Thread {

    private HetznerAccount account;
    private HetznerProcess process;
    private ServerProvider provider;
    private String domainId;

    @Override
    public void run() {
        try {
            if (!this.account.getEmpty()) {
                HetznerManager htznerMng = new HetznerManager();
                htznerMng.setAccount(this.account);
                Domain domin = new Domain(Integer.valueOf(TypesParser.safeParseInt(this.domainId)));
                if (domin.getEmpty()) {
                    throw new DatabaseException("No domain found in this process !");
                }
                String hostName = "mail." + domin.value;
                String rootPass = Strings.getSaltString(32, true, true, true, true);
                HashMap instances = htznerMng.createInstance(hostName, this.process.region, this.process.os, this.process.size, rootPass);
                if (instances == null) {
                    throw new DatabaseException("Hetzner Instance could not be created !");
                }
                ThreadSleep.sleep(60000L);
                Hetzner.updateCountInstancesCreated(1);
                UpdateData.updateInstancesCreated(this.process);
                HetznerInstance htznerInstance = new HetznerInstance();
                htznerInstance.status = "Running";
                htznerInstance.accountId = this.account.id;
                htznerInstance.accountName = this.account.name;
                htznerInstance.name = "mail." + domin.value + " (" + (String)instances.get("id") + ")";
                htznerInstance.instanceId = (String)instances.get("id");
                htznerInstance.region = (String)instances.get("region");
                htznerInstance.platform = (String)instances.get("image");
                htznerInstance.size = this.process.size;
                MtaServer mtaServ = getServerInfos(this.account, instances, this.provider, domin,rootPass);
                htznerInstance.mtaServerId = mtaServ.id;
                htznerInstance.mtaServerName = mtaServ.name;
                htznerInstance.createdBy = (Application.checkAndgetInstance().getUser()).email;
                htznerInstance.createdDate = new Date(System.currentTimeMillis());
                htznerInstance.lastUpdatedBy = (Application.checkAndgetInstance().getUser()).email;
                htznerInstance.lastUpdatedDate = new Date(System.currentTimeMillis());
                htznerInstance.insert();
                SSHConnector ssh = null;
                try {
                    ssh = Authentification.connectToServer(mtaServ);
                    if (ssh == null || !ssh.isConnected()) {
                        throw new DatabaseException("Could not connect to this Hetzner Instance : " + mtaServ.name + " !");
                    }
                    String prefix = "root".equals(mtaServ.sshUsername) ? "" : "sudo";
                    int version = String.valueOf(ssh.cmd("cat /etc/*release* | grep 'centos:7'")).replaceAll("\n", "").contains("centos:7") ? 7 : 6;
                    InstallationServices.installServices(ssh, mtaServ, prefix, version, false);
                    int vmtaId = InstallationServices.saveServerVmta(null, mtaServ.mainIp, "", domin.value, mtaServ);
                    String[] ips = ssh.cmd(prefix + "ip addr show | grep 'inet6' | grep -i 'global' | cut -f2 | awk '{ print $2}' | cut -f 1 -d '/' | awk '{ print $1}'").split("\n");
                    for (int i = 0; i < ips.length; i++) {
                        ips[i] = ips[i].replaceAll("\n", "").replaceAll("\r", "");
                    }
                    Database.get("system").executeUpdate("UPDATE admin.domains SET availability = 'Taken' , mta_server_id = '" + mtaServ.id + "', ip_id = '" + vmtaId + "' WHERE id = " + domin.id, null, Connector.AFFECTED_ROWS);
                    boolean updatedNetwork = CloudServices.setupNetwork(ssh, mtaServ, prefix, version, domin, mtaServ.mainIp, ips);
                    CloudServices.installTracking(ssh, mtaServ, prefix, version, domin, updatedNetwork, true);
                    InstallationServices.installPmta(ssh, mtaServ, prefix);
                    mtaServ.os = "CentOS " + version + " 64bits";
                    mtaServ.sshConnectivityStatus = "Connected";
                    mtaServ.setIpsCount(1 + ips.length);
                    mtaServ.setInstalled(true);
                    mtaServ.update();
                    Hetzner.updateCountInstancsInstalled();
                } finally {
                    if (ssh != null) {
                        ssh.disconnect();
                    }
                }
                UpdateData.updateInstancesInstalled(this.process);
            }
        } catch (Throwable th) {
            Loggers.error(th);
            Hetzner.IS_ERROR_OCCURED = true;
        }
    }

    public static synchronized MtaServer getServerInfos(HetznerAccount htznerAcc, HashMap instanceInfo, ServerProvider servProvider, Domain domin, String password) throws Exception {
        String name = htznerAcc.name.trim();
        String nm = name + "_1";
        List list = Database.get("system").availableTables("SELECT name FROM admin.mta_servers WHERE name LIKE '" + name + "%' ORDER BY id DESC LIMIT 1", null, 0, "name");
        if (list != null && !list.isEmpty()) {
            String str = String.valueOf(list.get(0));
            if (str.contains("_")) {
                String[] arrayOfString = str.split(Pattern.quote("_"));
                if (arrayOfString.length > 0){
                    nm = name + "_" + (TypesParser.safeParseInt(arrayOfString[arrayOfString.length - 1]) + 1);
                }
            }
        }
        Calendar calendar = Calendar.getInstance();
        calendar.add(2, 1);
        MtaServer mtaServ = new MtaServer();
        mtaServ.name = nm;
        mtaServ.status = "Activated";
        mtaServ.sshConnectivityStatus = "Not Checked";
        mtaServ.providerId = servProvider.id;
        mtaServ.providerName = servProvider.name;
        mtaServ.expirationDate = new Date(calendar.getTimeInMillis());
        mtaServ.hostName = "mail." + domin.value;
        mtaServ.mainIp = (String)instanceInfo.get("main_ip");
        String result = Agents.get("https://freegeoip.live/json/" + mtaServ.mainIp, null, 20);
        if (result != null && result.contains("country_code")) {
            JSONObject response = new JSONObject(result);
            mtaServ.countryCode = response.has("country_code") ? response.getString("country_code") : "US";
        } else {
            mtaServ.countryCode = "US";
        }
        mtaServ.oldSshPort = 22;
        mtaServ.sshPort = 22;
        mtaServ.sshLoginType = "user-pass";
        mtaServ.sshUsername = "root";
        mtaServ.sshPassword = password;
        mtaServ.oldSshPassword = (String)instanceInfo.get("password");
        mtaServ.createdBy = (Application.checkAndgetInstance().getUser()).email;
        mtaServ.createdDate = new Date(System.currentTimeMillis());
        mtaServ.lastUpdatedBy = (Application.checkAndgetInstance().getUser()).email;
        mtaServ.lastUpdatedDate = new Date(System.currentTimeMillis());
        mtaServ.id = mtaServ.insert();
        return mtaServ;
    }

    @ConstructorProperties({"account", "process", "provider", "domainId"})
    public InstancesManager(HetznerAccount account, HetznerProcess process, ServerProvider provider, String domainId) {
        this.account = account;
        this.process = process;
        this.provider = provider;
        this.domainId = domainId;
    }

    @Override
    public boolean equals(Object paramObject) {
        if (paramObject == this)
            return true;
        if (!(paramObject instanceof InstancesManager))
            return false;
        InstancesManager if1 = (InstancesManager)paramObject;
        if (!if1.exists(this))
            return false;
        HetznerAccount const1 = getAccount();
        HetznerAccount const2 = if1.getAccount();
        if ((const1 == null) ? (const2 != null) : !const1.equals(const2))
            return false;
        HetznerProcess float1 = getProcess();
        HetznerProcess float2 = if1.getProcess();
        if ((float1 == null) ? (float2 != null) : !float1.equals(float2))
            return false;
        ServerProvider default1 = getProvider();
        ServerProvider default2 = if1.getProvider();
        if ((default1 == null) ? (default2 != null) : !default1.equals(default2))
            return false;
        String str1 = getDomainId();
        String str2 = if1.getDomainId();
        return !((str1 == null) ? (str2 != null) : !str1.equals(str2));
    }

    protected boolean exists(Object paramObject) {
        return paramObject instanceof InstancesManager;
    }

    @Override
    public int hashCode() {
        int n = 1;
        HetznerAccount char1 = getAccount();
        n = n * 59 + ((char1 == null) ? 43 : char1.hashCode());
        HetznerProcess goto1 = getProcess();
        n = n * 59 + ((goto1 == null) ? 43 : goto1.hashCode());
        ServerProvider default1 = getProvider();
        n = n * 59 + ((default1 == null) ? 43 : default1.hashCode());
        String str1 = getDomainId();
        return n * 59 + ((str1 == null) ? 43 : str1.hashCode());
    }

    public HetznerAccount getAccount() {
        return account;
    }

    public void setAccount(HetznerAccount account) {
        this.account = account;
    }

    public HetznerProcess getProcess() {
        return process;
    }

    public void setProcess(HetznerProcess process) {
        this.process = process;
    }

    public ServerProvider getProvider() {
        return provider;
    }

    public void setProvider(ServerProvider provider) {
        this.provider = provider;
    }

    public String getDomainId() {
        return domainId;
    }

    public void setDomainId(String domainId) {
        this.domainId = domainId;
    }

    @Override
    public String toString() {
        return "InstancesManager(account=" + getAccount() + ", process=" + getProcess() + ", provider=" + getProvider() + ", domainId=" + getDomainId() + ")";
    }
}