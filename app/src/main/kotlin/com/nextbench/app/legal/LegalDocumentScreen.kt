package com.nextbench.app.legal

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nextbench.core.designsystem.NbDimens
import com.nextbench.core.designsystem.NbIcons
import com.nextbench.core.designsystem.NbLogo
import com.nextbench.core.designsystem.NbTheme

enum class LegalDocument { Terms, Privacy }

@Composable
fun LegalDocumentScreen(document: LegalDocument, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val content = when (document) {
        LegalDocument.Terms -> TermsContent
        LegalDocument.Privacy -> PrivacyContent
    }
    val context = LocalContext.current

    Column(modifier = modifier.fillMaxSize().background(NbTheme.colors.surfaceBase).statusBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = NbDimens.space8, vertical = NbDimens.space4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(NbIcons.Back, contentDescription = "Go back", tint = NbTheme.colors.ink)
            }
            NbLogo(size = NbDimens.avatarSm)
            Spacer(Modifier.width(NbDimens.space8))
            Text(content.shortTitle, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), color = NbTheme.colors.ink)
        }
        HorizontalDivider(color = NbTheme.colors.border)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = NbDimens.space20, vertical = NbDimens.space24),
            verticalArrangement = Arrangement.spacedBy(NbDimens.space24),
        ) {
            item(key = "header") {
                Column(verticalArrangement = Arrangement.spacedBy(NbDimens.space8)) {
                    Icon(content.icon, contentDescription = null, tint = NbTheme.colors.brandTeal, modifier = Modifier.size(28.dp))
                    Text(content.title, style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold), color = NbTheme.colors.ink)
                    Text("Last updated: ${content.lastUpdated}", style = MaterialTheme.typography.labelMedium, color = NbTheme.colors.inkMuted)
                    Text(content.introduction, style = MaterialTheme.typography.bodyLarge, color = NbTheme.colors.inkMuted)
                }
            }
            items(content.sections, key = LegalSection::title) { section ->
                LegalSectionContent(section)
            }
            item(key = "contact") {
                Column(verticalArrangement = Arrangement.spacedBy(NbDimens.space12)) {
                    HorizontalDivider(color = NbTheme.colors.border)
                    Text("Questions or privacy requests", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), color = NbTheme.colors.ink)
                    Text("Contact nextbench@loreto.edu. We aim to respond to verified requests within 30 days.", style = MaterialTheme.typography.bodyMedium, color = NbTheme.colors.inkMuted)
                    Row(
                        modifier = Modifier.clickable {
                            runCatching { context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:nextbench@loreto.edu"))) }
                        }.padding(vertical = NbDimens.space8),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(NbDimens.space8),
                    ) {
                        Icon(NbIcons.Messages, contentDescription = null, tint = NbTheme.colors.brandTeal, modifier = Modifier.size(20.dp))
                        Text("Email NextBench", style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold), color = NbTheme.colors.brandTeal)
                    }
                }
            }
        }
    }
}

@Composable
private fun LegalSectionContent(section: LegalSection) {
    Column(verticalArrangement = Arrangement.spacedBy(NbDimens.space12)) {
        Text(section.title, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold), color = NbTheme.colors.ink)
        section.blocks.forEach { block ->
            when (block) {
                is LegalBlock.Paragraph -> Text(block.text, style = MaterialTheme.typography.bodyMedium, color = NbTheme.colors.inkMuted)
                is LegalBlock.Bullets -> Column(verticalArrangement = Arrangement.spacedBy(NbDimens.space8)) {
                    block.subtitle?.let { subtitle ->
                        Text(subtitle, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold), color = NbTheme.colors.brandTeal)
                    }
                    block.items.forEach { item ->
                        Row(verticalAlignment = Alignment.Top) {
                            Text("-", style = MaterialTheme.typography.bodyMedium, color = NbTheme.colors.brandTeal)
                            Text(item, style = MaterialTheme.typography.bodyMedium, color = NbTheme.colors.inkMuted, modifier = Modifier.padding(start = NbDimens.space8).weight(1f))
                        }
                    }
                }
            }
        }
    }
}

private data class LegalContent(
    val shortTitle: String,
    val title: String,
    val lastUpdated: String,
    val introduction: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val sections: List<LegalSection>,
)

private data class LegalSection(val title: String, val blocks: List<LegalBlock>)

private sealed interface LegalBlock {
    data class Paragraph(val text: String) : LegalBlock
    data class Bullets(val subtitle: String? = null, val items: List<String>) : LegalBlock
}

private fun paragraph(text: String) = LegalBlock.Paragraph(text)
private fun bullets(vararg items: String, subtitle: String? = null) = LegalBlock.Bullets(subtitle, items.toList())

private val TermsContent = LegalContent(
    shortTitle = "Terms",
    title = "Terms of Service",
    lastUpdated = "May 12, 2026",
    introduction = "Please read these Terms of Service carefully before using the NextBench platform. By creating an account or using any part of our service, you agree to be bound by these terms in their entirety.",
    icon = NbIcons.Shield,
    sections = listOf(
        LegalSection("1. Eligibility", listOf(
            paragraph("NextBench is exclusively available to currently enrolled students of participating schools. By creating an account, you confirm that you are a genuine student of one of the listed institutions and that you are at least 13 years of age."),
            paragraph("All accounts must be verified via a valid, unaltered student ID card and a live selfie. Accounts found to be created with forged, expired, or borrowed identification will be permanently banned and reported to the relevant school administration."),
            paragraph("NextBench reserves the right to refuse or revoke access to any user at its sole discretion, particularly in cases of suspected fraud, abuse, or violation of these Terms."),
            paragraph("By creating an account, you expressly consent to AI-based identity verification, including analysis of your student ID card and selfie, and to the collection, processing, and storage of your personal data as described in our Privacy Policy. If you do not consent to these practices, you may not use the platform."),
        )),
        LegalSection("2. Account Responsibility", listOf(
            paragraph("You are solely responsible for all activity that occurs under your account. You must not share your login credentials or allow others to access your account."),
            paragraph("You agree to keep your profile information accurate and up to date. Misrepresentation of your identity, school, or listing details is grounds for immediate account termination."),
            paragraph("You are responsible for all transactions, messages, and conduct associated with your account, whether initiated by you or not."),
        )),
        LegalSection("3. Prohibited Items and Conduct", listOf(
            paragraph("The following items are strictly prohibited on NextBench: weapons, including replicas, controlled substances, alcohol, tobacco, adult content, counterfeit goods, stolen property, prescription medications, and any items illegal under applicable Indian law."),
            paragraph("You may not use NextBench for spam, phishing, harassment, hate speech, threats, or any form of deceptive conduct. Soliciting personal information from minors is strictly prohibited."),
            paragraph("Price gouging, artificial scarcity, and predatory pricing practices are prohibited. NextBench reserves the right to remove listings that are deemed unreasonably priced or exploitative."),
            paragraph("You may not scrape, crawl, or extract data from the platform using automated tools or bots."),
        )),
        LegalSection("4. Listings and Transactions", listOf(
            paragraph("All listings are submitted for admin review and must be approved before appearing publicly. NextBench reserves the right to reject any listing without explanation."),
            paragraph("NextBench acts solely as a platform to connect buyers and sellers. We are not a party to any transaction and bear no responsibility for the quality, safety, legality, or delivery of listed items."),
            paragraph("All payments, if any, are arranged directly between buyers and sellers. NextBench does not currently process payments and offers no escrow, payment protection, or refund service."),
            paragraph("We strongly recommend conducting all meetups at designated, safe, public locations, preferably school gates or other well-lit, populated areas during daylight hours."),
        )),
        LegalSection("5. Intellectual Property", listOf(
            paragraph("All content, branding, design, and code on the NextBench platform is the intellectual property of NextBench and its creators. You may not copy, reproduce, or distribute any part of the platform without express written permission."),
            paragraph("By uploading images or text to NextBench, you grant us a non-exclusive, royalty-free, worldwide licence to display and use that content solely for the purpose of operating the platform."),
            paragraph("You retain ownership of the content you upload. However, content that violates these Terms may be removed without notice."),
        )),
        LegalSection("6. Limitation of Liability", listOf(
            paragraph("NextBench is provided as is without warranties of any kind, expressed or implied. We do not guarantee uninterrupted, error-free, or secure access to the platform."),
            paragraph("To the fullest extent permitted by law, NextBench and its creators shall not be liable for any indirect, incidental, special, consequential, or punitive damages arising from your use of the platform, including loss of property, financial loss, or personal injury resulting from meetups."),
            paragraph("Your use of the platform is entirely at your own risk. You are solely responsible for taking appropriate safety precautions during any in-person transaction."),
        )),
        LegalSection("7. Account Termination", listOf(
            paragraph("We reserve the right to suspend or permanently terminate your account at any time, with or without notice, for any violation of these Terms."),
            paragraph("You may request deletion of your account by contacting us at the email address provided in the Privacy Policy. Upon deletion, your personal data will be removed from active databases within 30 days, subject to legal retention requirements."),
            paragraph("Upon termination, your active listings will be removed from the marketplace. Completed transaction records may be retained as required by law."),
        )),
        LegalSection("8. Governing Law", listOf(
            paragraph("These Terms are governed by the laws of the Republic of India, specifically the laws of the state of Uttar Pradesh, without regard to its conflict of law provisions."),
            paragraph("Any disputes arising from these Terms or your use of the platform shall be subject to the exclusive jurisdiction of the courts of Lucknow, Uttar Pradesh, India."),
            paragraph("If any provision of these Terms is found to be unenforceable, the remaining provisions shall remain in full force and effect."),
        )),
        LegalSection("9. Changes to Terms", listOf(
            paragraph("We reserve the right to modify these Terms at any time. Changes will be effective immediately upon posting to the platform. Continued use of NextBench after any changes constitutes your acceptance of the new Terms."),
            paragraph("We will make reasonable efforts to notify users of material changes via in-app notification or email."),
        )),
    ),
)

private val PrivacyContent = LegalContent(
    shortTitle = "Privacy",
    title = "Privacy Policy",
    lastUpdated = "May 12, 2026",
    introduction = "NextBench is built on trust. This Privacy Policy explains what data we collect, why we collect it, and how we protect it. We use plain language because you deserve to understand what happens with your information.",
    icon = NbIcons.Shield,
    sections = listOf(
        LegalSection("1. Information We Collect", listOf(
            paragraph("We collect the following categories of personal data when you use NextBench:"),
            bullets("Full name", "Email address", "Profile photo", "School or institution name", subtitle = "Account Information"),
            bullets("A photo of your student ID card", "A selfie holding your student ID card", subtitle = "Verification Documents"),
            bullets("Product listings, including photos, descriptions, and pricing", "Messaging data, including text and shared media", "Wishlist items and notification preferences", "Account and activity timestamps", subtitle = "Listing and Activity Data"),
            bullets("Device, browser, and operating system information", "IP address for security and abuse prevention", "Local app storage used for sessions and preferences", subtitle = "Technical Data"),
        )),
        LegalSection("2. How We Use Your Information", listOf(
            bullets("Verify identity and school enrollment", "Display profiles and listings to eligible users", "Facilitate communication between members", "Send account, listing, and message notifications", subtitle = "Core Service Delivery"),
            bullets("Detect and prevent fraud, abuse, and prohibited activity", "Review identity documents to maintain trust", "Investigate reported violations", subtitle = "Safety and Security"),
            bullets("Analyse aggregate, anonymised usage patterns", "Diagnose technical errors and performance issues", subtitle = "Platform Improvement"),
            paragraph("We do not sell, rent, or trade your personal data to third parties for marketing purposes. We do not use your data for advertising."),
        )),
        LegalSection("3. Data Storage and Third-Party Services", listOf(
            paragraph("Your data is processed and stored using trusted infrastructure providers that are bound by their own privacy commitments and data processing agreements."),
            bullets("Used for authentication, Firestore data, messaging, and cloud services", "Data is stored on Google Cloud infrastructure", "Governed by Google's Privacy Policy and Cloud Data Processing Addendum", subtitle = "Firebase and Google Cloud"),
            bullets("Used for secure storage and delivery of product images, chat files, and verification photos", "Media is processed and delivered through Cloudinary infrastructure", "Governed by Cloudinary's Privacy Policy", subtitle = "Cloudinary"),
            bullets("Used to host and serve the NextBench website", "Governed by Vercel's Privacy Policy", subtitle = "Vercel"),
        )),
        LegalSection("4. Verification Document Handling", listOf(
            paragraph("Verification photos, including student ID cards and selfies, are treated as sensitive personal data."),
            bullets("Visible only to authorised NextBench administrators for identity verification", "Never displayed to other users", "Stored in access-controlled cloud infrastructure", "Not used for advertising or unrelated purposes", subtitle = "Access and Use"),
            bullets("Retained while your account remains active when needed for re-verification", "Deleted within 30 days of a verified account deletion request", subtitle = "Retention"),
        )),
        LegalSection("5. Data Retention", listOf(
            paragraph("We retain personal data for as long as your account is active or as needed to provide the service."),
            bullets("Account data is retained until deletion is requested", "Verification documents are deleted within 30 days of a verified deletion request", "Completed transaction records may be retained for up to one year for disputes and legal compliance", "Chat messages remain with the conversation until removed under platform or account-deletion rules"),
        )),
        LegalSection("6. Your Rights", listOf(
            paragraph("Under the Digital Personal Data Protection Act, 2023 and applicable law, you may exercise the following rights:"),
            bullets("Request access to your personal data", "Request correction of inaccurate or incomplete data", "Request erasure of your account and associated data", "Withdraw consent", "Raise a privacy grievance"),
            paragraph("Contact us to exercise these rights. We aim to respond to verified requests within 30 days."),
        )),
        LegalSection("7. Local Storage", listOf(
            paragraph("NextBench uses secure device storage for essential purposes only."),
            bullets("Maintaining your authenticated session", "Storing temporary form data", "Remembering app preferences such as theme"),
            paragraph("We do not use advertising cookies or third-party advertising trackers."),
        )),
        LegalSection("8. Children's Privacy", listOf(
            paragraph("NextBench is not directed at children under 13. We do not knowingly collect personal data from children under 13."),
            paragraph("Users aged 13 to 18 are minors and must have parental consent to use the platform. By using the platform, they confirm that they have obtained such consent."),
            paragraph("If we become aware that a child under 13 has provided personal data, we will delete it promptly."),
        )),
        LegalSection("9. Security", listOf(
            bullets("TLS or HTTPS encryption for data in transit", "Firebase Security Rules for access control", "Restricted admin access to verification documents", "Access-controlled Cloudinary upload presets", "Authentication handled by Google and Firebase without storing user passwords", subtitle = "Security Measures"),
            paragraph("No system is completely secure. If a breach affects your rights, we will notify you as required by applicable law."),
        )),
        LegalSection("10. Contact Us", listOf(
            paragraph("For questions, concerns, data requests, or privacy complaints, contact nextbench@loreto.edu."),
        )),
        LegalSection("11. Your Consent", listOf(
            bullets("Collection and processing of account, school, profile, ID card, and selfie data as described here", "AI-assisted analysis of ID and selfie data for verification", "Secure storage with the providers described above", "Retention for the periods described in this policy", "In-app communications and service notifications", subtitle = "What You Consent To"),
            bullets("You may withdraw consent by requesting account deletion", "Withdrawal does not affect processing that occurred lawfully before withdrawal", "Do not create an account if you do not agree", "You may request access or correction at any time", subtitle = "Your Rights Regarding Consent"),
        )),
    ),
)
