/**
 *  This document is a part of the source code and related artifacts
 *  for CollectionSpace, an open source collections management system
 *  for museums and related institutions:

 *  http://www.collectionspace.org
 *  http://wiki.collectionspace.org

 *  Copyright 2011 University of California at Berkeley

 *  Licensed under the Educational Community License (ECL), Version 2.0.
 *  You may not use this file except in compliance with this License.

 *  You may obtain a copy of the ECL 2.0 License at

 *  https://source.collectionspace.org/collection-space/LICENSE.txt

 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.collectionspace.services.imports;

import java.io.*;
import java.util.UUID;

import org.collectionspace.services.common.IFragmentHandler;
import org.collectionspace.services.common.XmlSaxFragmenter;
import org.collectionspace.services.common.api.FileTools;
import org.collectionspace.services.common.api.Tools;
import org.dom4j.Document;
import org.dom4j.Element;
import org.hibernate.sql.Template;
import org.testng.annotations.Test;
import org.xml.sax.InputSource;

/** This class expands templates specifically for the imports service.
 *
 * @author Laramie Crocker
 */
public class TemplateExpander {

    protected static String var(String theVar){
        return "\\$\\{"+theVar+"\\}";
    }

    /**
     * @param source the template, which contains variables wrapped in a dollar sign and curly braces, e.g. source="my template with ID ${docID} yada yada."
     * @param theVar a variable name, without the dollar sign or curly braces or internal quotes, e.g. searchAndReplaceVar(source, "docID", "1234-5678")
     * @param replace the value the variable will be replaced with.
     * @return the expanded template.
     */
    public static String searchAndReplaceVar(String source, String theVar, String replace){
        return Tools.searchAndReplace(source, var(theVar), replace);
    }

    public static String doOneService(String outDir, String partTmpl, String wrapperTmpl,
                                      String SERVICE_TYPE, String SERVICE_NAME, String CSID) throws Exception {
        String docID;
        if (Tools.notBlank(CSID)){
            docID = CSID;
        } else {
            docID = UUID.randomUUID().toString();
        }
        String part = Tools.searchAndReplace(partTmpl, var("docID"), docID);

        wrapperTmpl = Tools.searchAndReplace(wrapperTmpl, var("Schema"), part);
        wrapperTmpl = Tools.searchAndReplace(wrapperTmpl, var("docID"), docID);
        wrapperTmpl = Tools.searchAndReplace(wrapperTmpl, var("ServiceType"), SERVICE_TYPE);
        wrapperTmpl = Tools.searchAndReplace(wrapperTmpl, var("ServiceName"), SERVICE_NAME);

        String serviceDir = outDir+'/'+docID;
        FileTools.saveFile(serviceDir, "document.xml", wrapperTmpl, true/*true=create parent dirs*/);
        return docID;
    }


    /** Once you have called createWorkspace() to create a home for documents of a service, you can call this method to add documents for that service.
     *
     *  Internally, this method also gets called by the XmlSaxFragmenter callback via the public inner class FragmentHandlerImpl.
     *
     * @param partTmpl  A template file that contains the schema part for the service, and which has macros such as ${docID} to be expanded.
     * @param SERVICE_NAME The name of the service, such as "Personauthorities"
     * @param SERVICE_TYPE The Nuxeo document type, such as "Personauthority"
     * @param TEMPLATE_DIR The local filesystem location of all the standard templates that wrap up workspace documents; once expanded, these spit out Nuxeo import format.
     * @param CSID An optional parameter which forces the document CSID, otherwise the CSID is set to a random UUID.
     * @throws Exception
     */
    public static void createDocInWorkspace(String partTmpl,
                                            String SERVICE_NAME,
                                            String SERVICE_TYPE,
                                            String TEMPLATE_DIR,
                                            String OUTPUT_DIR,
                                            String CSID) throws Exception {
        String wrapperTmpl = FileTools.readFile(TEMPLATE_DIR,"service-document.xml");
        String outputDir = OUTPUT_DIR+'/'+SERVICE_NAME;
        doOneService(outputDir, partTmpl, wrapperTmpl, SERVICE_TYPE, SERVICE_NAME, CSID);
    }

    /**  Call this method before calling createDocInWorkspace(), to make a directory such as "Personauthorities/" to receive documents
     *   for that service.
     *
     *   Internally, this method also gets called by the XmlSaxFragmenter callback via the public inner class FragmentHandlerImpl.
     *
     * @param SERVICE_NAME The name of the service, such as "Personauthorities"
     * @param TEMPLATE_DIR The local filesystem location of all the standard templates that wrap up workspace documents; once expanded, these spit out Nuxeo import format.
     * @throws Exception
     */
    public static void createWorkspace(String SERVICE_NAME, String TEMPLATE_DIR, String OUT_DIR) throws Exception {
        //TODO: set timestamp via creating a ${created} variable.
        String workspaceDoc = TEMPLATE_DIR+'/'+"workspace-document.xml";
        String serviceDir = OUT_DIR+'/'+SERVICE_NAME;
        FileTools.copyFile(workspaceDoc, serviceDir+"/document.xml", true);  //copy the template to the workspace
        String serviceDoc = FileTools.readFile(serviceDir, "/document.xml");  //now read the copy
        serviceDoc = TemplateExpander.searchAndReplaceVar(serviceDoc, "ServiceName", SERVICE_NAME);   //now expand macros in the copy
        FileTools.saveFile(serviceDir, "document.xml", serviceDoc, true);     //save the expanded copy in place.
    }

    public static void expand(String TEMPLATE_DIR, String outputDir, String requestFilename, String chopPath){
        FragmentHandlerImpl callback = new FragmentHandlerImpl(TEMPLATE_DIR, outputDir);
        XmlSaxFragmenter.parse(requestFilename, chopPath, callback, false);
    }

    public static void expandInputSource(String TEMPLATE_DIR, String outputDir, InputSource requestSource, String chopPath){
        FragmentHandlerImpl callback = new FragmentHandlerImpl(TEMPLATE_DIR, outputDir);
        XmlSaxFragmenter.parse(requestSource, chopPath, callback, false);
    }

    /** This inner class is the callback target for calls to XmlSaxFragmenter, for example:
     *     FragmentHandlerImpl callback = new FragmentHandlerImpl();
     *     XmlSaxFragmenter.parse(filename, "/imports/import", callback, false);
     *  It will be called for every /imports/import in the file, and each of those elements specify either
     *  service documents:
     *      &lt;import ID="1" service="Personauthorities" type="Personauthority">
     *  or workspaces:
     *      &lt;import ID="0" workspace="Personauthorities" />
     */
    public static class FragmentHandlerImpl implements IFragmentHandler {
        //============IFragmentHandler===========================================================
        public void onFragmentReady(Document context, Element fragmentParent, String currentPath, int fragmentIndex, String fragment){
            try {
                dump(context, currentPath, fragmentIndex, fragment);
                String workspace = fragmentParent.attributeValue("workspace");
                if (Tools.notEmpty(workspace)){
                    TemplateExpander.createWorkspace(workspace, TEMPLATE_DIR, OUPUT_DIR);//workspace is the serviceName
                }else {
                    String serviceName = checkAttribute(fragmentParent, "service", SERVICE_NAME);
                    String serviceType = checkAttribute(fragmentParent, "type", SERVICE_TYPE);
                    String CSID  = fragmentParent.attributeValue("CSID");
                    TemplateExpander.createDocInWorkspace(fragment, serviceName, serviceType, TEMPLATE_DIR, OUPUT_DIR, CSID);
                }
            } catch (Exception e){
                System.err.println("ERROR calling expandXmlPayloadToDir"+e);
                e.printStackTrace();
            }
        }
        public void onEndDocument(Document document, int fragmentCount){
            System.out.println("====TemplateExpander DONE============\r\n"+ XmlSaxFragmenter.prettyPrint(document)+"================");
        }
        //============helper methods==============================================================
        public FragmentHandlerImpl(String templateDir, String outputDir){
            TEMPLATE_DIR = templateDir;
            OUPUT_DIR = outputDir;
        }
        public String SERVICE_NAME = "";   //You can provide a default.
        public String SERVICE_TYPE = "";   //You can provide a default.
        public String TEMPLATE_DIR = "";   //You MUST provide a default via constructor.
        public String OUPUT_DIR = "";      //You MUST provide a default via constructor.
        private String checkAttribute(Element fragmentParent, String attName, String defaultVal){
            String val = fragmentParent.attributeValue(attName);
            if (Tools.notEmpty(val)){
                return val;
            }
            return defaultVal;
        }
        private void dump(Document context, String currentPath, int fragmentIndex, String fragment){
            System.out.println("====Path============\r\n"+currentPath+'['+fragmentIndex+']');
            System.out.println("====Context=========\r\n"+ XmlSaxFragmenter.prettyPrint(context));
            System.out.println("====Fragment========\r\n"+fragment+"\r\n===================\r\n");
        }
    }

}