/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.broadleafcommerce.cms.file.service;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.velocity.tools.view.ImportSupport;
import org.broadleafcommerce.cms.common.AbstractContentService;
import org.broadleafcommerce.cms.file.dao.StaticAssetDao;
import org.broadleafcommerce.cms.file.domain.StaticAsset;
import org.broadleafcommerce.cms.file.domain.StaticAssetImpl;
import org.broadleafcommerce.openadmin.server.dao.SandBoxItemDao;
import org.broadleafcommerce.openadmin.server.domain.SandBox;
import org.broadleafcommerce.openadmin.server.domain.SandBoxItem;
import org.broadleafcommerce.openadmin.server.domain.SandBoxItemType;
import org.broadleafcommerce.openadmin.server.domain.SandBoxOperationType;
import org.broadleafcommerce.openadmin.server.domain.SandBoxType;
import org.hibernate.Criteria;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.jsp.JspTagException;
import java.io.IOException;
import java.util.List;

/**
 * Created by bpolster.
 */
@Service("blStaticAssetService")
public class StaticAssetServiceImpl extends AbstractContentService implements StaticAssetService {

    private static final Log LOG = LogFactory.getLog(StaticAssetServiceImpl.class);
    
    protected String staticAssetUrlPrefix;
    protected String staticAssetEnvironmentUrlPrefix;
    protected String staticAssetEnvironmentSecureUrlPrefix;

    protected boolean automaticallyApproveAndPromoteStaticAssets=true;

    @Resource(name="blStaticAssetDao")
    protected StaticAssetDao staticAssetDao;

    @Resource(name="blSandBoxItemDao")
    protected SandBoxItemDao sandBoxItemDao;

    @Resource(name="blStaticAssetStorageService")
    protected StaticAssetStorageService staticAssetStorageService;

    @Override
    public StaticAsset findStaticAssetById(Long id) {
        return staticAssetDao.readStaticAssetById(id);
    }

    @Override
    public StaticAsset findStaticAssetByFullUrl(String fullUrl, SandBox targetSandBox) {
        return staticAssetDao.readStaticAssetByFullUrl(fullUrl, targetSandBox);
    }

    @Override
    public StaticAsset addStaticAsset(StaticAsset staticAsset, SandBox destinationSandbox) {
        
        if (automaticallyApproveAndPromoteStaticAssets) {           
            if (destinationSandbox != null && destinationSandbox.getSite() != null) {
                destinationSandbox = destinationSandbox.getSite().getProductionSandbox();
            } else {
                // Null means production for single-site installations.
                destinationSandbox = null;
            }            
        }
        
        staticAsset.setSandbox(destinationSandbox);
        staticAsset.setDeletedFlag(false);
        staticAsset.setArchivedFlag(false);
        StaticAsset newAsset = staticAssetDao.addOrUpdateStaticAsset(staticAsset, true);
        
        if (! isProductionSandBox(destinationSandbox)) {
            sandBoxItemDao.addSandBoxItem(destinationSandbox, SandBoxOperationType.ADD, SandBoxItemType.STATIC_ASSET, newAsset.getFullUrl(), newAsset.getId(), null);
        }
        return newAsset;
    }

    @Override
    public StaticAsset updateStaticAsset(StaticAsset staticAsset, SandBox destSandbox) {
        if (staticAsset.getLockedFlag()) {
            throw new IllegalArgumentException("Unable to update a locked record");
        }
        
        if (automaticallyApproveAndPromoteStaticAssets) {           
            if (destSandbox != null && destSandbox.getSite() != null) {
                destSandbox = destSandbox.getSite().getProductionSandbox();
            } else {
                // Null means production for single-site installations.
                destSandbox = null;
            }
        }

        if (checkForSandboxMatch(staticAsset.getSandbox(), destSandbox)) {
            if (staticAsset.getDeletedFlag()) {
                SandBoxItem item = sandBoxItemDao.retrieveBySandboxAndTemporaryItemId(staticAsset.getSandbox(), SandBoxItemType.STATIC_ASSET, staticAsset.getId());
                if (staticAsset.getOriginalAssetId() == null && item != null) {
                    // This item was added in this sandbox and now needs to be deleted.
                    staticAsset.setArchivedFlag(true);
                    item.setArchivedFlag(true);
                } else if (item != null) {
                    // This item was being updated but now is being deleted - so change the
                    // sandbox operation type to deleted
                    item.setSandBoxOperationType(SandBoxOperationType.DELETE);
                    sandBoxItemDao.updateSandBoxItem(item);
                }
            }
            return staticAssetDao.addOrUpdateStaticAsset(staticAsset, true);
        } else if (isProductionSandBox(staticAsset.getSandbox())) {
            // Move from production to destSandbox
            StaticAsset clonedAsset = staticAsset.cloneEntity();
            clonedAsset.setOriginalAssetId(staticAsset.getId());
            clonedAsset.setSandbox(destSandbox);
            StaticAsset returnAsset = staticAssetDao.addOrUpdateStaticAsset(clonedAsset, true);

            StaticAsset prod = findStaticAssetById(staticAsset.getId());
            prod.setLockedFlag(true);
            staticAssetDao.addOrUpdateStaticAsset(prod, false);

            SandBoxOperationType type = SandBoxOperationType.UPDATE;
            if (clonedAsset.getDeletedFlag()) {
                type = SandBoxOperationType.DELETE;
            }

            sandBoxItemDao.addSandBoxItem(destSandbox, type, SandBoxItemType.STATIC_ASSET, returnAsset.getFullUrl(), returnAsset.getId(), returnAsset.getOriginalAssetId());
            return returnAsset;
        } else {
            // This should happen via a promote, revert, or reject in the sandbox service
            throw new IllegalArgumentException("Update called when promote or reject was expected.");
        }
    }

    // Returns true if the src and dest sandbox are the same.
    private boolean checkForSandboxMatch(SandBox src, SandBox dest) {
        if (src != null) {
            if (dest != null) {
                return src.getId().equals(dest.getId());
            }
        }
        return (src == null && dest == null);
    }

    // Returns true if the dest sandbox is production.
    private boolean checkForProductionSandbox(SandBox dest) {
        boolean productionSandbox = false;

        if (dest == null) {
            productionSandbox = true;
        } else {
            if (dest.getSite() != null && dest.getSite().getProductionSandbox() != null && dest.getSite().getProductionSandbox().getId() != null) {
                productionSandbox = dest.getSite().getProductionSandbox().getId().equals(dest.getId());
            }
        }

        return productionSandbox;
    }

    // Returns true if the dest sandbox is production.
    private boolean isProductionSandBox(SandBox dest) {
        if (dest == null) {
            return true;
        } else {
            return SandBoxType.PRODUCTION.equals(dest.getSandBoxType());
        }
    }

    @Override
    public void deleteStaticAsset(StaticAsset staticAsset, SandBox destinationSandbox) {
        staticAsset.setDeletedFlag(true);
        updateStaticAsset(staticAsset, destinationSandbox);
    }

    @Override
    public List<StaticAsset> findAssets(SandBox sandbox, Criteria c) {
        return (List<StaticAsset>) findItems(sandbox, c, StaticAsset.class, StaticAssetImpl.class, "originalAssetId");
    }

    @Override
    public Long countAssets(SandBox sandbox, Criteria c) {
       return countItems(sandbox, c, StaticAssetImpl.class, "originalAssetId");
    }

    @Override
    public void itemPromoted(SandBoxItem sandBoxItem, SandBox destinationSandBox) {
        if (! SandBoxItemType.STATIC_ASSET.equals(sandBoxItem.getSandBoxItemType())) {
            return;
        }
        StaticAsset asset = staticAssetDao.readStaticAssetById(sandBoxItem.getTemporaryItemId());

        if (asset == null) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Asset not found " + sandBoxItem.getTemporaryItemId());
            }
        } else {
            boolean productionSandBox = isProductionSandBox(destinationSandBox);
            if (productionSandBox) {
                asset.setLockedFlag(false);
            } else {
                asset.setLockedFlag(true);
            }
            if (productionSandBox && asset.getOriginalAssetId() != null) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Asset promoted to production.  " + asset.getId() + ".  Archiving original asset " + asset.getOriginalAssetId());
                }
                StaticAsset originalAsset = staticAssetDao.readStaticAssetById(sandBoxItem.getTemporaryItemId());
                originalAsset.setArchivedFlag(Boolean.TRUE);
                staticAssetDao.addOrUpdateStaticAsset(originalAsset, false);
                asset.setOriginalAssetId(null);

                if (asset.getDeletedFlag()) {
                    asset.setArchivedFlag(Boolean.TRUE);
                }
            }
        }
        if (asset.getOriginalSandBox() == null) {
            asset.setOriginalSandBox(asset.getSandbox());
        }
        asset.setSandbox(destinationSandBox);
        staticAssetDao.addOrUpdateStaticAsset(asset, false);
    }

    @Override
    public void itemRejected(SandBoxItem sandBoxItem, SandBox destinationSandBox) {
        if (! SandBoxItemType.STATIC_ASSET.equals(sandBoxItem.getSandBoxItemType())) {
            return;
        }
        StaticAsset asset = staticAssetDao.readStaticAssetById(sandBoxItem.getTemporaryItemId());

        if (asset != null) {
            asset.setSandbox(destinationSandBox);
            asset.setOriginalSandBox(null);
            asset.setLockedFlag(false);
            staticAssetDao.addOrUpdateStaticAsset(asset, false);
        }
    }

    @Override
    public void itemReverted(SandBoxItem sandBoxItem) {
        if (! SandBoxItemType.STATIC_ASSET.equals(sandBoxItem.getSandBoxItemType())) {
            return;
        }
        StaticAsset asset = staticAssetDao.readStaticAssetById(sandBoxItem.getTemporaryItemId());

        if (asset != null) {
            asset.setArchivedFlag(Boolean.TRUE);
            asset.setLockedFlag(false);
            staticAssetDao.addOrUpdateStaticAsset(asset, false);

            StaticAsset originalAsset = staticAssetDao.readStaticAssetById(sandBoxItem.getOriginalItemId());
            originalAsset.setLockedFlag(false);
            staticAssetDao.addOrUpdateStaticAsset(originalAsset, false);
        }
    }


    public String getStaticAssetUrlPrefix() {
        return staticAssetUrlPrefix;
    }

    public void setStaticAssetUrlPrefix(String staticAssetUrlPrefix) {
        this.staticAssetUrlPrefix = staticAssetUrlPrefix;
    }

    public String getStaticAssetEnvironmentUrlPrefix() {
        return fixEnvironmentUrlPrefix(staticAssetEnvironmentUrlPrefix);
    }

    public void setStaticAssetEnvironmentUrlPrefix(String staticAssetEnvironmentUrlPrefix) {
        this.staticAssetEnvironmentUrlPrefix = staticAssetEnvironmentUrlPrefix;
    }

    public String getStaticAssetEnvironmentSecureUrlPrefix() {
        if (staticAssetEnvironmentSecureUrlPrefix == null) {
            if (staticAssetEnvironmentUrlPrefix != null && staticAssetEnvironmentUrlPrefix.indexOf("http:") >= 0) {
                staticAssetEnvironmentSecureUrlPrefix = staticAssetEnvironmentUrlPrefix.replace("http:", "https:");
            }
        }
        return fixEnvironmentUrlPrefix(staticAssetEnvironmentSecureUrlPrefix);
    }

    public void setStaticAssetEnvironmentSecureUrlPrefix(String staticAssetEnvironmentSecureUrlPrefix) {        
        this.staticAssetEnvironmentSecureUrlPrefix = staticAssetEnvironmentSecureUrlPrefix;
    }

    public boolean getAutomaticallyApproveAndPromoteStaticAssets() {
        return automaticallyApproveAndPromoteStaticAssets;
    }

    public void setAutomaticallyApproveAndPromoteStaticAssets(boolean automaticallyApproveAndPromoteStaticAssets) {
        this.automaticallyApproveAndPromoteStaticAssets = automaticallyApproveAndPromoteStaticAssets;
    }

    /**
     * Trims whitespace.   If the value is the same as the internal url prefix, then return
     * null.
     *
     * @param urlPrefix
     * @return
     */
    private String fixEnvironmentUrlPrefix(String urlPrefix) {
        if (urlPrefix != null) {
            urlPrefix = urlPrefix.trim();
            if ("".equals(urlPrefix)) {
                // The value was not set.
                urlPrefix = null;
            } else if (urlPrefix.equals(staticAssetUrlPrefix)) {
                // The value is the same as the default, so no processing needed.
                urlPrefix = null;
            }
        }
        return urlPrefix;
    }

    /**
     * This method will take in an assetPath (think image url) and convert it if
     * the value contains the asseturlprefix.
     * 
     * Will append any contextPath onto the request.
     *
     * @param assetPath     - The path to rewrite if it is a cms managed asset
     * @param contextPath   - The context path of the web application (if applicable)
     * @param secureRequest - True if the request is being served over https
     * @return
     * @see org.broadleafcommerce.cms.file.service.StaticAssetService#getStaticAssetUrlPrefix()
     * @see org.broadleafcommerce.cms.file.service.StaticAssetService#getStaticAssetEnvironmentUrlPrefix()
     */
    @Override
    public String convertAssetPath(String assetPath, String contextPath, boolean secureRequest) {
        String returnValue = assetPath;
        
        if (assetPath != null && staticAssetUrlPrefix != null) {
            if (assetPath.contains(staticAssetUrlPrefix)) {
                final String envPrefix;
                if (secureRequest) {
                    envPrefix = getStaticAssetEnvironmentSecureUrlPrefix();
                } else {
                    envPrefix = getStaticAssetEnvironmentUrlPrefix();
                }
                if (envPrefix != null && ! envPrefix.equals(staticAssetUrlPrefix)) {
                    returnValue = returnValue.replace(staticAssetUrlPrefix, envPrefix);            
                }
            }
        }

        if (! ImportSupport.isAbsoluteUrl(returnValue)) {
            if (! returnValue.startsWith("/")) {
                returnValue = "/" + returnValue;
            }
            
            // Add context path
            if (contextPath != null && ! contextPath.equals("")) {
                if (! contextPath.equals("/")) {
                    if (! contextPath.startsWith("/")) {
                        returnValue = contextPath + returnValue;  // normal case
                    } else {
                        returnValue = "/" + contextPath + returnValue;
                    }
                }
            }
        }
        return returnValue;
    }
}
