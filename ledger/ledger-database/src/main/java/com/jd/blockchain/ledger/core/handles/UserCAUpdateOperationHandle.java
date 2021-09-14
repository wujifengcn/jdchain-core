package com.jd.blockchain.ledger.core.handles;

import com.jd.blockchain.ca.CertificateRole;
import com.jd.blockchain.ca.CertificateUtils;
import com.jd.blockchain.ledger.LedgerPermission;
import com.jd.blockchain.ledger.UserCAUpdateOperation;
import com.jd.blockchain.ledger.core.EventManager;
import com.jd.blockchain.ledger.core.LedgerQuery;
import com.jd.blockchain.ledger.core.LedgerTransactionContext;
import com.jd.blockchain.ledger.core.MultiIDsPolicy;
import com.jd.blockchain.ledger.core.OperationHandleContext;
import com.jd.blockchain.ledger.core.SecurityContext;
import com.jd.blockchain.ledger.core.SecurityPolicy;
import com.jd.blockchain.ledger.core.TransactionRequestExtension;

import java.security.cert.X509Certificate;
import java.util.Arrays;

public class UserCAUpdateOperationHandle extends AbstractLedgerOperationHandle<UserCAUpdateOperation> {

    public UserCAUpdateOperationHandle() {
        super(UserCAUpdateOperation.class);
    }

    @Override
    protected void doProcess(UserCAUpdateOperation op, LedgerTransactionContext transactionContext,
                             TransactionRequestExtension requestContext, LedgerQuery ledger, OperationHandleContext handleContext, EventManager manager) {
        // 权限校验；
        SecurityPolicy securityPolicy = SecurityContext.getContextUsersPolicy();
        securityPolicy.checkEndpointPermission(LedgerPermission.UPDATE_USER_CA, MultiIDsPolicy.AT_LEAST_ONE);

        // 证书校验
        X509Certificate cert = CertificateUtils.parseCertificate(op.getCertificate());
        CertificateUtils.checkCertificateRolesAny(cert, CertificateRole.PEER, CertificateRole.GW, CertificateRole.USER);
        CertificateUtils.checkValidity(cert);
        X509Certificate[] ledgerCAs = CertificateUtils.parseCertificates(transactionContext.getDataset().getAdminDataset().getMetadata().getLedgerCertificates());
        X509Certificate[] issuers = CertificateUtils.findIssuers(cert, ledgerCAs);
        Arrays.stream(issuers).forEach(issuer -> CertificateUtils.checkCACertificate(issuer));
        CertificateUtils.checkValidityAny(issuers);

        // 操作账本；
        transactionContext.getDataset().getUserAccountSet().setCertificate(op.getUserAddress(), op.getCertificate());
    }

}
